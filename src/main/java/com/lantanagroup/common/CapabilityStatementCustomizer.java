package com.lantanagroup.common;

import java.io.InputStream;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;

@Interceptor
public class CapabilityStatementCustomizer {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CapabilityStatementCustomizer.class);

  FhirContext fhirContext;
  String key;

  public CapabilityStatementCustomizer(FhirContext fhirContext, String key) {
    this.fhirContext = fhirContext;
    this.key = key;
  }

  @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
  public IBaseConformance customize(IBaseConformance theCapabilityStatement) {

    String fileName = "CapabilityStatement-" + key + ".json";

    try {

      DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
      Resource resource = resourceLoader.getResource(fileName);
      InputStream inputStream = resource.getInputStream();

      IBaseConformance capabilityStatement = (IBaseConformance) fhirContext.newJsonParser().parseResource(inputStream);

      return capabilityStatement;

    } catch (Exception e) {
      logger.error("Failed to load CapabilityStatment with filename " + fileName + ": " + e.getMessage(), e);
      return null;
    }

  }

}
