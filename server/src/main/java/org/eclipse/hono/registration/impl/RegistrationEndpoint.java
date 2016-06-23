/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.registration.impl;

import static org.eclipse.hono.registration.RegistrationConstants.APP_PROPERTY_MESSAGE_ID;
import static org.eclipse.hono.registration.RegistrationConstants.EVENT_BUS_ADDRESS_REGISTRATION_IN;
import static org.eclipse.hono.util.MessageHelper.APP_PROPERTY_RESOURCE_ID;
import static org.eclipse.hono.util.MessageHelper.getLinkName;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.registration.RegistrationConstants;
import org.eclipse.hono.registration.RegistrationMessageFilter;
import org.eclipse.hono.server.BaseEndpoint;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.ResourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import io.vertx.proton.impl.ProtonReceiverImpl;

/**
 * A Hono {@code Endpoint} for managing devices.
 */
public final class RegistrationEndpoint extends BaseEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationEndpoint.class);

    private Map<String, ProtonSender> replySenderMap = new HashMap<>();

    public RegistrationEndpoint(final Vertx vertx, final boolean singleTenant) {
        super(Objects.requireNonNull(vertx), singleTenant, 0);
    }

    public RegistrationEndpoint(final Vertx vertx, final boolean singleTenant, final int instanceId) {
        super(Objects.requireNonNull(vertx), singleTenant, instanceId);
    }

    @Override
    public String getName() {
        return RegistrationConstants.REGISTRATION_ENDPOINT;
    }

    @Override
    public void onLinkAttach(final ProtonReceiver receiver, final ResourceIdentifier targetAddress) {
        if (ProtonQoS.AT_LEAST_ONCE.equals(receiver.getRemoteQoS())) {
            LOG.debug("client wants to use AT LEAST ONCE delivery mode, ignoring ...");
        }
        final ProtonReceiverImpl receiverImpl = (ProtonReceiverImpl) receiver;

        receiver.closeHandler(clientDetached -> onLinkDetach(clientDetached.result()))
                .handler((delivery, message) -> {

                    LOG.debug("incoming message [{}]: {}", receiverImpl.getName(), message);
                    LOG.debug("app properties: {}", message.getApplicationProperties());

                    if (RegistrationMessageFilter.verify(targetAddress, message)) {
                        sendRegistrationData(delivery, message);
                    } else {
                        onLinkDetach(receiver);
                    }
                }).open();

        LOG.debug("registering new link for client [{}]", receiverImpl.getName());
    }

    @Override
    public void onLinkAttach(final ProtonSender sender, final ResourceIdentifier targetResource) {

        final org.apache.qpid.proton.amqp.messaging.Source source = (org.apache.qpid.proton.amqp.messaging.Source) sender.getSource();

        if (targetResource.getDeviceId() == null) {
            LOG.debug("Client must provide a reply address e.g. registration/<tenant>/1234-abc");
            sender.setCondition(ProtonHelper.condition("amqp:invalid-field", "link target must have the following format registration/<tenant>/<reply-address>"));
            sender.close();
        }

        final String linkName = MessageHelper.getLinkName(sender);
        LOG.info("link: {}", linkName);
        LOG.info("source: {}", sender.getSource());
        LOG.info("remote source: {}", sender.getRemoteSource());
        LOG.info("target: {}", sender.getTarget());
        LOG.info("remote target: {}", sender.getRemoteTarget());

        final MessageConsumer<JsonObject> replyConsumer = vertx.eventBus().consumer(source.getAddress(), message -> {
            // TODO check for correct session here...?
            LOG.trace("Forwarding reply to client: {}", message.body());
            final Message amqpReply = RegistrationConstants.getAmqpReply(message);
            sender.send(amqpReply);
        });

        sender.closeHandler(closed -> {
            replyConsumer.unregister();
            LOG.debug("Receiver closed link {}, removing associated event bus consumer {}", linkName, replyConsumer.address());
        });

        sender.open();
    }

    private void onLinkDetach(final ProtonReceiver client) {
        LOG.debug("closing receiver for client [{}]", getLinkName(client));
        client.close();
    }

    private void sendRegistrationData(final ProtonDelivery delivery, final Message msg) {
        final ResourceIdentifier messageAddress = ResourceIdentifier.fromString(
                MessageHelper.getAnnotation(msg, APP_PROPERTY_RESOURCE_ID));
        checkPermission(messageAddress, permissionGranted -> {
            if (permissionGranted) {
                vertx.runOnContext(run -> {
                    final JsonObject registrationMsg = RegistrationConstants.getRegistrationMsg(msg);
                    vertx.eventBus().send(EVENT_BUS_ADDRESS_REGISTRATION_IN, registrationMsg,
                            result -> {
                                // TODO check for correct session here...?
                                final String replyTo = msg.getReplyTo();
                                if (replyTo != null) {
                                    final JsonObject message = (JsonObject) result.result().body();
                                    message.put(APP_PROPERTY_MESSAGE_ID, msg.getMessageId());
                                    vertx.eventBus().send(replyTo, message);
                                } else {
                                    LOG.debug("No reply-to address provided, cannot send reply to client.");
                                }
                            });
                    ProtonHelper.accepted(delivery, true);
                });
            } else {
                LOG.debug("client is not authorized to register devices at [{}]", messageAddress);
            }
        });
    }
}
