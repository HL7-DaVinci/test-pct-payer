package com.lantanagroup.common;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.lantanagroup.notification.PctSubscriptionTopicProfileInterceptor;
import com.lantanagroup.notification.SubscriptionNotificationInterceptor;
import com.lantanagroup.providers.GfeSubmitProvider;
import com.lantanagroup.providers.SystemValidateProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers the PCT providers and interceptors on the RestfulServer the starter builds.
 * Registration runs while the Spring context refreshes, which is before the servlet
 * initializes the server, so the same ordering as the former per-server config is kept.
 */
@Component
public class PctPayerServerCustomizer {

  private final RestfulServer restfulServer;
  private final IInterceptorService interceptorService;
  private final DaoRegistry daoRegistry;
  private final SubscriptionNotificationInterceptor subscriptionNotificationInterceptor;

  public PctPayerServerCustomizer(
      RestfulServer restfulServer,
      IInterceptorService interceptorService,
      DaoRegistry daoRegistry,
      SubscriptionNotificationInterceptor subscriptionNotificationInterceptor) {
    this.restfulServer = restfulServer;
    this.interceptorService = interceptorService;
    this.daoRegistry = daoRegistry;
    this.subscriptionNotificationInterceptor = subscriptionNotificationInterceptor;
  }

  @PostConstruct
  public void register() {
    restfulServer.registerInterceptor(
        new CapabilityStatementCustomizer(restfulServer.getFhirContext(), "davincipct"));
    interceptorService.registerInterceptor(new PctSubscriptionTopicProfileInterceptor());
    interceptorService.registerInterceptor(subscriptionNotificationInterceptor);

    restfulServer.registerProviders(
        new GfeSubmitProvider(restfulServer.getFhirContext(), daoRegistry),
        new SystemValidateProvider(daoRegistry));
  }
}
