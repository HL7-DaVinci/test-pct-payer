package ca.uhn.fhir.jpa.starter.interceptors;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;

import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;

import ca.uhn.fhir.rest.annotation.Search;

import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.searchparam.matcher.IndexedSearchParamExtractor;
import ca.uhn.fhir.jpa.searchparam.extractor.ResourceIndexedSearchParams;

import ca.uhn.fhir.context.FhirContext;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.Date;
import java.util.EnumMap;
import java.util.function.Function;
import java.util.*;
import java.io.*;
import ca.uhn.fhir.jpa.provider.*;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.instance.model.api.*;
// import org.hl7.fhir.dstu2.model.BaseDateTimeType;
// import org.hl7.fhir.r4.model.Bundle;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.rest.client.api.*;
import ca.uhn.fhir.parser.*;

// import org.json.simple.JSONArray;
// import org.json.simple.JSONObject;
// import org.json.simple.parser.JSONParser;
// import org.json.simple.parser.ParseException;
// import ca.uhn.fhir.jpa.starter.utils.JSONWrapper;
import ca.uhn.fhir.jpa.starter.utils.RequestHandler;

/**
 * Class for intercepting and handling the subsciptions
 */
@Interceptor
public class BundleInterceptor {
   private final Logger myLogger = LoggerFactory.getLogger(BundleInterceptor.class.getName());

 	 private FhirContext myCtx;

   private String baseUrl;

   private IGenericClient client;

   private RequestHandler requestHandler;
   private IParser jparser;
   // private JSONParser parser;

   private InMemoryResourceMatcher matcher;
   private IndexedSearchParamExtractor extractor;

   /**
    * Constructor using a specific logger
    */
   public BundleInterceptor() {
       configure("localhost:8080", null);
   }

   public BundleInterceptor(String url, FhirContext ctx) {
      configure(url, ctx);
   }
   /**
    * Used for constructors to initilize variables
    * @param url The url for the fhir server
    * @param ctx the fhir context
    */
   private void configure(String url, FhirContext ctx) {
        baseUrl = url;
        myCtx = ctx;
        client = myCtx.newRestfulGenericClient(baseUrl + "/fhir");
        requestHandler = new RequestHandler();
        // jparser = myCtx.newJsonParser();
        // parser = new JSONParser();
   }
   /**
    * Set the base url
    * @param url the url
    */
   public void setBaseUrl(String url) {
      baseUrl = url;
   }
   /**
    * Override the incomingRequestPostProcessed method, which is called
    * for each request after it has been processed. If it is a Task resource then the subscriptions
    * will be checked
    * @param theRequest
    * @param theResponse
    * @param theResource this is the resource that was posted to the server
    * @param theRequest
    * @return whether to continue
    */
   @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
   public boolean incomingRequestPostProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse, IBaseResource theResource, RequestDetails theDetails) {
     String[] parts = theRequest.getRequestURL().toString().split("/");
     // Here is where the Bundle Topic should be evaluated
     if (theRequest.getMethod().equals("POST") && theResource.fhirType().equals("Bundle")) {
         myLogger.info("Received Bundle");
         myLogger.info(theResource.getIdElement().getValue());
     }
     return true;
  }

}
