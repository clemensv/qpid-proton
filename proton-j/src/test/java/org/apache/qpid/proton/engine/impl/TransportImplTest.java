/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.proton.engine.impl;

import static org.apache.qpid.proton.engine.impl.AmqpHeader.HEADER;
import static org.apache.qpid.proton.engine.impl.TransportTestHelper.stringOfLength;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.LinkedList;

import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.transport.Attach;
import org.apache.qpid.proton.amqp.transport.Begin;
import org.apache.qpid.proton.amqp.transport.FrameBody;
import org.apache.qpid.proton.amqp.transport.Open;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Transport;
import org.apache.qpid.proton.engine.TransportException;
import org.apache.qpid.proton.framing.TransportFrame;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TransportImplTest
{
    @SuppressWarnings("deprecation")
    private TransportImpl _transport = new TransportImpl();

    private static final int CHANNEL_ID = 1;
    private static final TransportFrame TRANSPORT_FRAME_BEGIN = new TransportFrame(CHANNEL_ID, new Begin(), null);
    private static final TransportFrame TRANSPORT_FRAME_OPEN = new TransportFrame(CHANNEL_ID, new Open(), null);

    @Rule
    public ExpectedException _expectedException = ExpectedException.none();

    @Test
    public void testInput()
    {
        ByteBuffer buffer = _transport.getInputBuffer();
        buffer.put(HEADER);
        _transport.processInput().checkIsOk();

        assertNotNull(_transport.getInputBuffer());
    }

    @Test
    public void testInitialProcessIsNoop()
    {
        _transport.process();
    }

    @Test
    public void testProcessIsIdempotent()
    {
        _transport.process();
        _transport.process();
    }

    /**
     * Empty input is always allowed by {@link Transport#getInputBuffer()} and
     * {@link Transport#processInput()}, in contrast to the old API.
     *
     * @see TransportImplTest#testEmptyInputBeforeBindUsingOldApi_causesTransportException()
     */
    @Test
    public void testEmptyInput_isAllowed()
    {
        _transport.getInputBuffer();
        _transport.processInput().checkIsOk();
    }

    /**
     * Tests the end-of-stream behaviour specified by {@link Transport#input(byte[], int, int)}.
     */
    @Test
    public void testEmptyInputBeforeBindUsingOldApi_causesTransportException()
    {
        _expectedException.expect(TransportException.class);
        _expectedException.expectMessage("Unexpected EOS when remote connection not closed: connection aborted");
        _transport.input(new byte [0], 0, 0);
    }

    /**
     * TODO it's not clear why empty input is specifically allowed in this case.
     */
    @Test
    public void testEmptyInputWhenRemoteConnectionIsClosedUsingOldApi_isAllowed()
    {
        @SuppressWarnings("deprecation")
        ConnectionImpl connection = new ConnectionImpl();
        _transport.bind(connection);
        connection.setRemoteState(EndpointState.CLOSED);
        _transport.input(new byte [0], 0, 0);
    }

    @Test
    public void testOutupt()
    {
        {
            // TransportImpl's underlying output spontaneously outputs the AMQP header
            final ByteBuffer outputBuffer = _transport.getOutputBuffer();
            assertEquals(HEADER.length, outputBuffer.remaining());

            byte[] outputBytes = new byte[HEADER.length];
            outputBuffer.get(outputBytes);
            assertArrayEquals(HEADER, outputBytes);

            _transport.outputConsumed();
        }

        {
            final ByteBuffer outputBuffer = _transport.getOutputBuffer();
            assertEquals(0, outputBuffer.remaining());
            _transport.outputConsumed();
        }
    }

    @Test
    public void testTransportInitiallyHandlesFrames()
    {
        assertTrue(_transport.isHandlingFrames());
    }

    @Test
    public void testBoundTransport_continuesToHandleFrames()
    {
        @SuppressWarnings("deprecation")
        Connection connection = new ConnectionImpl();

        assertTrue(_transport.isHandlingFrames());

        _transport.bind(connection);

        assertTrue(_transport.isHandlingFrames());

        _transport.handleFrame(TRANSPORT_FRAME_OPEN);

        assertTrue(_transport.isHandlingFrames());
    }

    @Test
    public void testUnboundTransport_stopsHandlingFrames()
    {
        assertTrue(_transport.isHandlingFrames());

        _transport.handleFrame(TRANSPORT_FRAME_OPEN);

        assertFalse(_transport.isHandlingFrames());
    }

    @Test
    public void testHandleFrameWhenNotHandling_throwsIllegalStateException()
    {
        assertTrue(_transport.isHandlingFrames());

        _transport.handleFrame(TRANSPORT_FRAME_OPEN);

        assertFalse(_transport.isHandlingFrames());

        _expectedException.expect(IllegalStateException.class);
        _transport.handleFrame(TRANSPORT_FRAME_BEGIN);
    }

    @Test
    public void testOutputTooBigToBeWrittenInOneGo()
    {
        int smallMaxFrameSize = 512;
        _transport = new TransportImpl(smallMaxFrameSize);

        @SuppressWarnings("deprecation")
        Connection conn = new ConnectionImpl();
        _transport.bind(conn);

        // Open frame sized in order to produce a frame that will almost fill output buffer
        conn.setHostname(stringOfLength("x", 500));
        conn.open();

        // Close the connection to generate a Close frame which will cause an overflow
        // internally - we'll get the remaining bytes on the next interaction.
        conn.close();

        ByteBuffer buf = _transport.getOutputBuffer();
        assertEquals("Expecting buffer to be full", smallMaxFrameSize, buf.remaining());
        buf.position(buf.limit());
        _transport.outputConsumed();

        buf  = _transport.getOutputBuffer();
        assertTrue("Expecting second buffer to have bytes", buf.remaining() > 0);
        assertTrue("Expecting second buffer to not be full", buf.remaining() < Transport.MIN_MAX_FRAME_SIZE);
    }

    @Test
    public void testAttemptToInitiateSaslAfterProcessingBeginsCausesIllegalStateException()
    {
        _transport.process();

        try
        {
            _transport.sasl();
        }
        catch(IllegalStateException ise)
        {
            //expected, sasl must be initiated before processing begins
        }
    }

    private class MockTransportImpl extends TransportImpl
    {
        LinkedList<FrameBody> writes = new LinkedList<FrameBody>();
        @Override
        protected void writeFrame(int channel, FrameBody frameBody,
                                  ByteBuffer payload, Runnable onPayloadTooLarge) {
            super.writeFrame(channel, frameBody, payload, onPayloadTooLarge);
            writes.addLast(frameBody);
        }
    }

    @Test
    public void testTickRemoteTimeout()
    {
        MockTransportImpl transport = new MockTransportImpl();
        Connection connection = Proton.connection();
        transport.bind(connection);

        int timeout = 4000;
        Open open = new Open();
        open.setIdleTimeOut(new UnsignedInteger(4000));
        TransportFrame openFrame = new TransportFrame(CHANNEL_ID, open, null);
        transport.handleFrame(openFrame);
        while(transport.pending() > 0) transport.pop(transport.head().remaining());

        long deadline = transport.tick(0);
        assertEquals("Expected to be returned a deadline of 2000",  2000, deadline);  // deadline = 4000 / 2

        deadline = transport.tick(1000);    // Wait for less than the deadline with no data - get the same value
        assertEquals("When the deadline hasn't been reached tick() should return the previous deadline",  2000, deadline);
        assertEquals("When the deadline hasn't been reached tick() shouldn't write data", 0, transport.writes.size());

        deadline = transport.tick(timeout/2); // Wait for the deadline - next deadline should be (4000/2)*2
        assertEquals("When the deadline has been reached expected a new deadline to be returned 4000",  4000, deadline);
        assertEquals("tick() should have written data", 1, transport.writes.size());
        assertEquals("tick() should have written an empty frame", null, transport.writes.get(0));

        transport.writeFrame(CHANNEL_ID, new Begin(), null, null);
        while(transport.pending() > 0) transport.pop(transport.head().remaining());
        int framesWrittenBeforeTick = transport.writes.size();
        deadline = transport.tick(3000);
        assertEquals("Writing data resets the deadline",  5000, deadline);
        assertEquals("When the deadline is reset tick() shouldn't write an empty frame", 0, transport.writes.size() - framesWrittenBeforeTick);

        transport.writeFrame(CHANNEL_ID, new Attach(), null, null);
        assertTrue(transport.pending() > 0);
        framesWrittenBeforeTick = transport.writes.size();
        deadline = transport.tick(4000);
        assertEquals("Having pending data does not reset the deadline",  5000, deadline);
        assertEquals("Having pending data prevents tick() from sending an empty frame", 0, transport.writes.size() - framesWrittenBeforeTick);
    }

    @Test
    public void testTickLocalTimeout()
    {
        MockTransportImpl transport = new MockTransportImpl();
        transport.setIdleTimeout(6000);
        Connection connection = Proton.connection();
        transport.bind(connection);

        transport.handleFrame(TRANSPORT_FRAME_OPEN);
        connection.open();
        while(transport.pending() > 0) transport.pop(transport.head().remaining());

        long deadline = transport.tick(0);
        assertEquals("Expected to be returned a deadline of 4000",  4000, deadline);

        int framesWrittenBeforeTick = transport.writes.size();
        deadline = transport.tick(1000);    // Wait for less than the deadline with no data - get the same value
        assertEquals("When the deadline hasn't been reached tick() should return the previous deadline",  4000, deadline);
        assertEquals("Reading data should never result in a frame being written", 0, transport.writes.size() - framesWrittenBeforeTick);

        // Protocol header + empty frame
        ByteBuffer data = ByteBuffer.wrap(new byte[] {'A', 'M', 'Q', 'P', 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08, 0x02, 0x00, 0x00, 0x00});
        while (data.remaining() > 0)
        {
            int origLimit = data.limit();
            int amount = Math.min(transport.tail().remaining(), data.remaining());
            data.limit(data.position() + amount);
            transport.tail().put(data);
            data.limit(origLimit);
            transport.process();
        }
        framesWrittenBeforeTick = transport.writes.size();
        deadline = transport.tick(2000);
        assertEquals("Reading data data resets the deadline",  6000, deadline);
        assertEquals("Reading data should never result in a frame being written", 0, transport.writes.size() - framesWrittenBeforeTick);
        assertEquals("Reading data before the deadline should keep the connection open", EndpointState.ACTIVE, connection.getLocalState());

        framesWrittenBeforeTick = transport.writes.size();
        deadline = transport.tick(7000);
        assertEquals("Calling tick() after the deadline should result in the connection being closed", EndpointState.CLOSED, connection.getLocalState());
    }
}
