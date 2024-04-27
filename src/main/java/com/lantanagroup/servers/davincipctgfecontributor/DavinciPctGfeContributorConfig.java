package com.lantanagroup.servers.davincipctgfecontributor;

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

import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.config.r4.JpaR4Config;
import ca.uhn.fhir.jpa.starter.common.FhirServerConfigCommon;
import ca.uhn.fhir.jpa.starter.common.StarterJpaConfig;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.jpa.subscription.match.config.SubscriptionProcessorConfig;
import ca.uhn.fhir.jpa.subscription.submit.config.SubscriptionSubmitterConfig;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;


@Configuration
@ComponentScan(basePackageClasses = { DavinciPctGfeContributorConfig.class })
@PropertySource("classpath:davincipctgfecontributor.properties")
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
  JpaBatch2Config.class,
	Batch2JobsConfig.class
})
public class DavinciPctGfeContributorConfig extends CommonConfig {

  @Autowired
  protected DavinciPctGfeContributorProperties serverProperties;

  @Primary
  @Bean
  public DataSourceProperties dataSourceProperties() {
    return serverProperties.getDatasource();
  }

  @Bean
  public ServletRegistrationBean<RestfulServer> fhirServletRegistrationBean(RestfulServer restfulServer) {

    restfulServer.registerInterceptor(new ResponseHighlighterInterceptor());
    restfulServer.registerInterceptor(new CapabilityStatementCustomizer(restfulServer.getFhirContext(), "davincipctgfecontributor"));

    ServletRegistrationBean<RestfulServer> registration = new ServletRegistrationBean<>(restfulServer, "/fhir/*");
    registration.setLoadOnStartup(1);
    return registration;
  }
  
}
