package com.lantanagroup.notification;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import org.hl7.fhir.instance.model.api.IBaseResource;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatchRequest;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import org.hl7.fhir.r4.model.DocumentReference;

public class SubscriptionNotificationInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionNotificationInterceptor.class);

    private static final String TOPIC_AEOB_AVAILABLE_AUTHOR = "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-aeob-available-author-notification";

    private static final String TOPIC_AEOB_AVAILABLE_SUBJECT = "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-aeob-available-subject-notification";

    private final SubscriptionTopicDispatcher subscriptionTopicDispatcher;

    private final SearchParamMatcher searchParamMatcher;

    public SubscriptionNotificationInterceptor(SubscriptionTopicDispatcher subscriptionTopicDispatcher, SearchParamMatcher searchParamMatcher) {
        this.subscriptionTopicDispatcher = subscriptionTopicDispatcher;
        this.searchParamMatcher = searchParamMatcher;
    }

    private static final String[] DOCUMENT_REFERENCE_TOPICS = {
            TOPIC_AEOB_AVAILABLE_AUTHOR,
            TOPIC_AEOB_AVAILABLE_SUBJECT
    };

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onResourceCreated(IBaseResource resource) {
        if (resource == null) return;
        String resourceType = resource.getClass().getSimpleName();
        String resourceId = getResourceId(resource);
        logger.debug("STORAGE_PRECOMMIT_RESOURCE_CREATED: {} [ID: {}]", resourceType, resourceId);
        dispatchResourceNotification(resource, RestOperationTypeEnum.CREATE);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void onResourceUpdated(
            IBaseResource theOldResource,
            IBaseResource theNewResource,
            RequestDetails theRequestDetails) {
        if (theNewResource == null) return;

        String resourceType = theNewResource.getClass().getSimpleName();
        String resourceId = getResourceId(theNewResource);
        logger.debug("STORAGE_PRECOMMIT_RESOURCE_UPDATED: {} [ID: {}]", resourceType, resourceId);
        dispatchResourceNotification(theNewResource, RestOperationTypeEnum.UPDATE);
    }

    private void dispatchResourceNotification(IBaseResource resource, RestOperationTypeEnum opType) {
        String[] topics;
        if (resource instanceof DocumentReference && opType != RestOperationTypeEnum.DELETE) {
            topics = DOCUMENT_REFERENCE_TOPICS;
            logger.info("dispatchResourceNotification: matched DocumentReference for op={}, dispatching to {} topics", opType, topics.length);
        } else {
            logger.info("dispatchResourceNotification: no topic mapped for {} op={}", resource.getClass().getSimpleName(), opType);
            return;
        }

        GenericResourceFilterMatcher matcher = new GenericResourceFilterMatcher(searchParamMatcher);
        String id = getResourceId(resource);

        for (String topicUrl : topics) {
            SubscriptionTopicDispatchRequest request = new SubscriptionTopicDispatchRequest(
                topicUrl,
                Collections.singletonList(resource),
                matcher,
                opType,
                null,
                null,
                null
            );
            logger.debug("Dispatching {} [ID: {}] op={} topic={}", resource.getClass().getSimpleName(), id, opType, topicUrl);
            subscriptionTopicDispatcher.dispatch(request);
        }
    }

    private String getResourceId(IBaseResource resource) {
        return (resource != null && resource.getIdElement() != null) ? resource.getIdElement().getIdPart() : "unknown";
    }
}
