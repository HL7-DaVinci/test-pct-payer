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
import ca.uhn.fhir.jpa.starter.utils.FileLoader;

/**
 * Class for intercepting and handling the subsciptions
 */
@Interceptor
public class GFEInterceptor {
   private final Logger myLogger = LoggerFactory.getLogger(GFEInterceptor.class.getName());

   private String baseUrl;

   // private IGenericClient client;
   //
   private RequestHandler requestHandler;
   // private IParser jparser;
   // // private JSONParser parser;

   // private InMemoryResourceMatcher matcher;
   // private IndexedSearchParamExtractor extractor;

   /**
    * Constructor using a specific logger
    */
   public GFEInterceptor() {
       // configure("http://localhost:8081/fhir", null);
       baseUrl = "http://localhost:8081/fhir";
       requestHandler = new RequestHandler();
   }

   // public GFEInterceptor(String url, FhirContext ctx) {
   //    configure(url, ctx);
   // }
   // /**
   //  * Used for constructors to initilize variables
   //  * @param url The url for the fhir server
   //  * @param ctx the fhir context
   //  */
   // private void configure(String url, FhirContext ctx) {
   //      baseUrl = url;
   //      myCtx = ctx;
   //      client = myCtx.newRestfulGenericClient(baseUrl + "/fhir");
   //      requestHandler = new RequestHandler();
   //      // jparser = myCtx.newJsonParser();
   //      // parser = new JSONParser();
   // }
   /**
    * Set the base url
    * @param url the url
    */
   public void setBaseUrl(String url) {
      baseUrl = url;
   }
   /**
    * Override the incomingRequestPreProcessed method, which is called
    * for each incoming request before any processing is done
    * @param  theRequest  the request to the server
    * @param  theResponse the response from the server
    * @return             whether to continue processing
    */
   @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
   public boolean incomingRequestPreProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) {
     String[] parts = theRequest.getRequestURI().toString().split("/");
     // Here is where the Claim Topic should be evaluated
     System.out.println("Intercepted the request pl");
     System.out.println(theRequest.getRequestURI().toString());
     myLogger.info("Intercepted the request");
     //myLogger.info(parts);
     for (String p: parts) {
        System.out.println(parts);
     }
     // System.out.println(theRequest.getRequestURI().toString());
     if (parts.length > 3 && parts[2].equals("Claim") && parts[3].equals("$gfe-submit")) {
         myLogger.info("Received Submit");
         System.out.println("pl received submit");
         try {
            handleSubmit(theResponse);
         } catch (Exception e) {
            myLogger.info("Error in submission");
            myLogger.info(e.getMessage());
            e.printStackTrace();
         }
         return false;
     }
     return true;
  }
  public void handleSubmit(HttpServletResponse theResponse) throws Exception {
      theResponse.setStatus(200);
      String outputString = FileLoader.loadResource("Bundle-GFE.json");
      System.out.println("Loaded Resource");
      // myLogger.info(outputString);
      myLogger.info(baseUrl + "/Bundle");
      String result = requestHandler.sendPost(baseUrl + "/Bundle", outputString);
      myLogger.info("RESULT");
      myLogger.info(result);
      // PrintWriter out = theResponse.getWriter();
      // theResponse.setContentType("application/json");
      // theResponse.setCharacterEncoding("UTF-8");
      // out.print(outputString);
      // out.flush();
  }

}
