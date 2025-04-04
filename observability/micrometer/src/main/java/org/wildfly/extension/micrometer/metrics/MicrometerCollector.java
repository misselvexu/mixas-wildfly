/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.micrometer.metrics;

import static org.jboss.as.controller.PathAddress.EMPTY_ADDRESS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ATTRIBUTES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.wildfly.extension.micrometer.metrics.MetricMetadata.Type.COUNTER;
import static org.wildfly.extension.micrometer.metrics.MetricMetadata.Type.GAUGE;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.LocalModelControllerClient;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessStateNotifier;
import org.jboss.as.controller.client.helpers.MeasurementUnit;
import org.jboss.as.controller.descriptions.DescriptionProvider;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.wildfly.extension.micrometer.registry.WildFlyRegistry;

public class MicrometerCollector implements AutoCloseable {
    private final LocalModelControllerClient modelControllerClient;
    private final ProcessStateNotifier processStateNotifier;
    private final WildFlyRegistry micrometerRegistry;
    private final Predicate<String> subsystemFilter;

    public MicrometerCollector(LocalModelControllerClient modelControllerClient,
                               ProcessStateNotifier processStateNotifier,
                               WildFlyRegistry micrometerRegistry,
                               Predicate<String> subsystemFilter) {
        this.modelControllerClient = modelControllerClient;
        this.processStateNotifier = processStateNotifier;
        this.micrometerRegistry = micrometerRegistry;
        this.subsystemFilter = subsystemFilter;
    }

    // collect metrics from the resources
    public synchronized MetricRegistration collectResourceMetrics(final Resource resource,
                                                    ImmutableManagementResourceRegistration mrr,
                                                    Function<PathAddress, PathAddress> addressResolver) {
        MetricRegistration registration = new MetricRegistration(micrometerRegistry);

        queueMetricRegistration(resource, mrr, EMPTY_ADDRESS, addressResolver, registration);
        // Defer the actual registration until the server is running, and they can be collected w/o errors
        this.processStateNotifier.addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (ControlledProcessState.State.RUNNING == evt.getNewValue()) {
                    registration.register();
                } else if (ControlledProcessState.State.STOPPING == evt.getNewValue()) {
                    // Unregister so if this is a reload they won't still be around in a static cache in MetricsRegistry
                    // and cause problems when the server is starting
                    registration.unregister();
                    processStateNotifier.removePropertyChangeListener(this);
                }

            }
        });

        // If server is already running, we won't get a change event so register now
        if (ControlledProcessState.State.RUNNING == this.processStateNotifier.getCurrentState()) {
            registration.register();
        }

        return registration;
    }

    @Override
    public void close() throws Exception {
        micrometerRegistry.close();
    }

    private void queueMetricRegistration(final Resource current,
                                         ImmutableManagementResourceRegistration mrr,
                                         PathAddress address,
                                         Function<PathAddress, PathAddress> addressResolver,
                                         MetricRegistration registration) {
        if (!isExposingMetrics(address, subsystemFilter)) {
            return;
        }

        Map<String, AttributeAccess> attributes = mrr.getAttributes(address);
        if (attributes == null) {
            return;
        }

        ModelNode resourceDescription = null;

        for (Map.Entry<String, AttributeAccess> entry : attributes.entrySet()) {
            AttributeAccess attributeAccess = entry.getValue();
            if (!isCollectibleMetric(attributeAccess)) {
                continue;
            }

            if (resourceDescription == null) {
                DescriptionProvider modelDescription = mrr.getModelDescription(address);
                resourceDescription = modelDescription.getModelDescription(Locale.getDefault());
            }
            PathAddress resourceAddress = addressResolver.apply(address);
            String attributeName = entry.getKey();
            MeasurementUnit unit = attributeAccess.getAttributeDefinition().getMeasurementUnit();
            boolean isCounter = attributeAccess.getFlags().contains(AttributeAccess.Flag.COUNTER_METRIC);
            String attributeDescription = resourceDescription.get(ATTRIBUTES, attributeName, DESCRIPTION).asStringOrNull();

            WildFlyMetric metric = new WildFlyMetric(modelControllerClient, resourceAddress, attributeName);
            WildFlyMetricMetadata metadata = new WildFlyMetricMetadata(attributeName, resourceAddress,
                    attributeDescription, unit, isCounter ? COUNTER : GAUGE);

            registration.addRegistrationTask(() -> registration.registerMetric(metric, metadata));
        }

        for (String type : current.getChildTypes()) {
            for (Resource.ResourceEntry entry : current.getChildren(type)) {
                final PathElement pathElement = entry.getPathElement();
                final PathAddress childAddress = address.append(pathElement);
                queueMetricRegistration(entry, mrr, childAddress, addressResolver, registration);
            }
        }
    }

    private boolean isExposingMetrics(PathAddress address, Predicate<String> subsystemFilter) {
        // root resource
        if (address.size() == 0) {
            return true;
        }
        String subsystemName = getSubsystemName(address);
        return subsystemName != null && subsystemFilter.test(subsystemName);
    }

    private String getSubsystemName(PathAddress address) {
        if (address.size() == 0) {
            return null;
        }
        if (address.getElement(0).getKey().equals(SUBSYSTEM)) {
            return address.getElement(0).getValue();
        } else {
            return getSubsystemName(address.subAddress(1));
        }
    }

    private boolean isCollectibleMetric(AttributeAccess attributeAccess) {
        if (attributeAccess.getAccessType() == AttributeAccess.AccessType.METRIC
                && attributeAccess.getStorageType() == AttributeAccess.Storage.RUNTIME) {
            // handle only metrics with simple numerical types
            ModelType type = attributeAccess.getAttributeDefinition().getType();
            return type == ModelType.INT ||
                    type == ModelType.LONG ||
                    type == ModelType.DOUBLE;
        }
        return false;
    }
}
