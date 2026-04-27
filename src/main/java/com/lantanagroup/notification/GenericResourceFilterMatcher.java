package com.lantanagroup.notification;

import ca.uhn.fhir.jpa.topic.filter.ISubscriptionTopicFilterMatcher;
import ca.uhn.fhir.jpa.subscription.model.CanonicalTopicSubscriptionFilter;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericResourceFilterMatcher implements ISubscriptionTopicFilterMatcher {

    private static final Logger logger = LoggerFactory.getLogger(GenericResourceFilterMatcher.class);

    private final SearchParamMatcher mySearchParamMatcher;

    public GenericResourceFilterMatcher(SearchParamMatcher searchParamMatcher) {
        this.mySearchParamMatcher = searchParamMatcher;
    }

    @Override
    public InMemoryMatchResult match(CanonicalTopicSubscriptionFilter theCanonicalTopicSubscriptionFilter, IBaseResource theIBaseResource) {
        String paramName = theCanonicalTopicSubscriptionFilter.getFilterParameter();
        String paramValue = theCanonicalTopicSubscriptionFilter.getValue();

        if (paramName == null || paramValue == null) {
            logger.warn("GenericResourceFilterMatcher received null filter parameter or value; returning no-match");
            return InMemoryMatchResult.noMatch();
        }

        String resourceType = theIBaseResource.fhirType();
        String resourceId = theIBaseResource.getIdElement() != null ? theIBaseResource.getIdElement().getIdPart() : "unknown";
        String criteria = paramName + "=" + paramValue;
        InMemoryMatchResult result = mySearchParamMatcher.match(criteria, theIBaseResource, null);
        logger.info("PCT Generic Resource Filter Matcher Result: [ID: {}] [Filter: ={}={}] [Matched: {}] {}",
                resourceId, paramName, paramValue,  result.matched(), resourceType);
        return result;
    }

}
