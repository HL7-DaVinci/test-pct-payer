package com.lantanagroup.common;

import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import com.lantanagroup.notification.SubscriptionNotificationInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans the PCT payer server adds on top of the HAPI starter. The starter picks this
 * class up through hapi.fhir.custom-bean-packages in application.yaml.
 */
@Configuration
public class PctPayerServerConfig {


  @Bean
  public SubscriptionNotificationInterceptor subscriptionNotificationInterceptor(
      SubscriptionTopicDispatcher dispatcher, SearchParamMatcher searchParamMatcher) {
    return new SubscriptionNotificationInterceptor(dispatcher, searchParamMatcher);
  }
}
