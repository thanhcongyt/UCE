/*
 * Copyright (c) 2012 Alexander Diener,
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.fhkn.in.uce.holepunching.core.source;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fhkn.in.uce.holepunching.core.ConnectionListener;
import de.fhkn.in.uce.holepunching.core.HolePuncher;
import de.fhkn.in.uce.holepunching.core.HolePunchingUtil;
import de.fhkn.in.uce.holepunching.message.HolePunchingMethod;
import de.fhkn.in.uce.stun.attribute.MappedAddress;
import de.fhkn.in.uce.stun.attribute.Token;
import de.fhkn.in.uce.stun.header.STUNMessageClass;
import de.fhkn.in.uce.stun.header.STUNMessageMethod;
import de.fhkn.in.uce.stun.message.Message;
import de.fhkn.in.uce.stun.message.MessageReader;
import de.fhkn.in.uce.stun.message.MessageStaticFactory;
import de.fhkn.in.uce.stun.message.MessageWriter;

/**
 * Implementation of hole punching source side for parallel hole punching. To
 * get a socket connection to a hole punching target it sends a connection
 * request message with the id of the target to the mediator and waits for the
 * forward endpoints message. After it received the message that contains the
 * public and private endpoint of the target it establishes a connection to the
 * target with the help of the {@link ConnectionListener} and
 * {@link HolePuncher} class.
 * 
 * @author Daniel Maier, Alexander Diener (aldiener@htwg-konstanz.de)
 * 
 */
public final class HolePunchingSource {
    private final static Logger logger = LoggerFactory.getLogger(HolePunchingSource.class);
    private final HolePunchingUtil hpUtil;
    private final Socket controlConnection;

    /**
     * Creates a {@link HolePunchingSource}.
     */
    public HolePunchingSource() {
        this.hpUtil = HolePunchingUtil.getInstance();
        this.controlConnection = new Socket();
    }

    /**
     * Returns a socket that is connected with the given targetId.
     * 
     * @param targetId
     *            the id of the target
     * @param mediatorAddress
     *            the mediator address
     * @return a socket that is connected to the target
     * @throws IOException
     */
    public Socket getSocket(final String targetId, final SocketAddress mediatorAddress) throws IOException {
        Socket result = null;
        logger.debug("Trying to connect to {}", targetId); //$NON-NLS-1$
        this.connectToMediator(mediatorAddress);
        this.sendConnectionRequest();
        final Message receivedMessage = this.receiveMessage();
        if (this.isForwardedEndpointsMessage(receivedMessage)) {
            logger.debug("Received forwarding endpoints message"); //$NON-NLS-1$
            final List<MappedAddress> addresses = receivedMessage.getAttributes(MappedAddress.class);
            final BlockingQueue<Socket> socketQueue = new ArrayBlockingQueue<Socket>(1);
            final InetSocketAddress localAddress = (InetSocketAddress) this.controlConnection.getLocalSocketAddress();
            final ConnectionListener connectionListener = new ConnectionListener(localAddress.getAddress(),
                    localAddress.getPort());
            logger.debug("Starting hole puncher"); //$NON-NLS-1$
            final SourceConnectionAuthenticator authentification = new SourceConnectionAuthenticator(receivedMessage
                    .getAttribute(Token.class).getToken());
            final HolePuncher hp = new HolePuncher(connectionListener, localAddress, socketQueue);
            this.startHolePunching(addresses, authentification, hp);
            boolean interrupted = false;
            try {
                while (result == null) {
                    try {
                        result = socketQueue.take();
                    } catch (final InterruptedException e) {
                        interrupted = true;
                        // fall through and retry
                        logger.info("InterruptedException (fall through and retry)");
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            connectionListener.shutdown();
            hp.shutdownNow();
            // received dummy socket for indicating time limit exceeded
            if (!result.isConnected()) {
                throw new IOException("Could not get socket to: " + targetId);
            }
            logger.info("Returning socket: {}", result);
        } else {
            logger.debug("Forwarded Endpoints message expected but was {}", receivedMessage.getMessageMethod()
                    .toString());
        }
        return result;
    }

    private void connectToMediator(final SocketAddress mediatorAddress) throws IOException {
        this.controlConnection.setReuseAddress(true);
        this.controlConnection.connect(mediatorAddress);
    }

    private void sendConnectionRequest() throws IOException {
        final MessageWriter messageWriter = new MessageWriter(this.controlConnection.getOutputStream());
        final Message connectionRequestMessage = MessageStaticFactory.newSTUNMessageInstance(STUNMessageClass.REQUEST,
                STUNMessageMethod.CONNECTION_REQUEST);
        final InetSocketAddress localAddress = (InetSocketAddress) this.controlConnection.getLocalSocketAddress();
        connectionRequestMessage.addAttribute(new MappedAddress(localAddress));
        messageWriter.writeMessage(connectionRequestMessage);
    }

    private Message receiveMessage() throws IOException {
        final MessageReader messageReader = this.hpUtil.getCustomHolePunchingMessageReader();
        return messageReader.readSTUNMessage(this.controlConnection.getInputStream());
    }

    private boolean isForwardedEndpointsMessage(final Message toCheck) {
        return toCheck.isMethod(HolePunchingMethod.FORWARDED_ENDPOINTS) && toCheck.hasAttribute(MappedAddress.class)
                && toCheck.hasAttribute(Token.class);
    }

    private void startHolePunching(final List<MappedAddress> endpoints,
            final SourceConnectionAuthenticator authentification, final HolePuncher hp) {
        // hole puncher expects exactly two endpoints, if more
        // endpoints are provided use the first two
        final InetSocketAddress endpointOne = endpoints.get(0).getEndpoint();
        final InetSocketAddress endpointTwo = endpoints.get(1).getEndpoint();
        hp.establishHolePunchingConnection(endpointOne.getAddress(), endpointOne.getPort(), endpointTwo.getAddress(),
                endpointTwo.getPort(), authentification);
    }
}