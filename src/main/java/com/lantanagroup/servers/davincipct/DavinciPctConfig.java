package com.lantanagroup.servers.davincipct;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.subscription.match.matcher.subscriber.SubscriptionMatchDeliverer;
import ca.uhn.fhir.jpa.subscription.match.registry.SubscriptionRegistry;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicPayloadBuilder;
import com.lantanagroup.notification.SubscriptionNotificationInterceptor;
import com.lantanagroup.notification.SubscriptionWebSocketConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;

import com.lantanagroup.common.CapabilityStatementCustomizer;
import com.lantanagroup.common.CommonConfig;
import com.lantanagroup.providers.GfeSubmitProvider;

import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.config.r4.JpaR4Config;
import ca.uhn.fhir.jpa.starter.common.FhirServerConfigCommon;
import ca.uhn.fhir.jpa.starter.common.StarterJpaConfig;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.jpa.subscription.match.config.SubscriptionProcessorConfig;
import ca.uhn.fhir.jpa.subscription.submit.config.SubscriptionSubmitterConfig;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;


@Configuration
@ComponentScan(basePackages = { "com.lantanagroup.servers.davincipct", "ca.uhn.fhir.jpa.starter.datainitializer" })
@PropertySource("classpath:davincipct.properties")
@EnableAutoConfiguration(exclude = {
  ElasticsearchRestClientAutoConfiguration.class
})
@Import({
  JpaR4Config.class,
  StarterJpaConfig.class,
  FhirServerConfigCommon.class,
  SubscriptionSubmitterConfig.class,
	SubscriptionProcessorConfig.class,
	SubscriptionChannelConfig.class,
    SubscriptionWebSocketConfig.class,
  JpaBatch2Config.class,
	Batch2JobsConfig.class
})
public class DavinciPctConfig extends CommonConfig {

  @Autowired
  protected DavinciPctProperties serverProperties;

  @Autowired
  protected DaoRegistry daoRegistry;

  @Primary
  @Bean
  public DataSourceProperties dataSourceProperties() {
    return serverProperties.getDatasource();
  }

  @Bean
  public SubscriptionNotificationInterceptor subscriptionNotificationInterceptor(SubscriptionTopicDispatcher dispatcher, SearchParamMatcher searchParamMatcher) {
    return new SubscriptionNotificationInterceptor(dispatcher, searchParamMatcher);
  }

  @Bean
  public ServletRegistrationBean<RestfulServer> fhirServletRegistrationBean(RestfulServer restfulServer, SubscriptionNotificationInterceptor subscriptionNotificationInterceptor) {

    restfulServer.registerInterceptor(new ResponseHighlighterInterceptor());
    restfulServer.registerInterceptor(new CapabilityStatementCustomizer(restfulServer.getFhirContext(), "davincipct"));
    restfulServer.registerInterceptor(subscriptionNotificationInterceptor);
    // Switched to preloading resources using DataInitializer.
    //restfulServer.registerInterceptor(new ProcessCustomizer(restfulServer.getFhirContext(), daoRegistry, "davincipctcoordinationplatform"));

    restfulServer.registerProviders(
        new GfeSubmitProvider(restfulServer.getFhirContext(), daoRegistry)
    );

    ServletRegistrationBean<RestfulServer> registration = new ServletRegistrationBean<>(restfulServer, "/fhir/*");
    registration.setLoadOnStartup(1);
    return registration;
  }

  @Bean
  public SubscriptionTopicDispatcher subscriptionTopicDispatcher(
          FhirContext fhirContext,
          SubscriptionRegistry subscriptionRegistry,
          SubscriptionMatchDeliverer subscriptionMatchDeliverer,
          SubscriptionTopicPayloadBuilder subscriptionTopicPayloadBuilder
  ) {
    return new SubscriptionTopicDispatcher(
            fhirContext,
            subscriptionRegistry,
            subscriptionMatchDeliverer,
            subscriptionTopicPayloadBuilder
    );
  }
  
}
