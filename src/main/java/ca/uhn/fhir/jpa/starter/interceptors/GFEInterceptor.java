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
import ca.uhn.fhir.rest.api.MethodOutcome;

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
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.rest.client.api.*;
import ca.uhn.fhir.parser.*;

import ca.uhn.fhir.jpa.starter.utils.RequestHandler;
import ca.uhn.fhir.jpa.starter.utils.FileLoader;

/**
 * Class for intercepting and handling the subsciptions
 */
@Interceptor
public class GFEInterceptor {
   private final Logger myLogger = LoggerFactory.getLogger(GFEInterceptor.class.getName());

   private String baseUrl = "http://localhost:8080";//"https://davinci-pct-payer.logicahealth.org";////

   private IGenericClient client;
   //
   private RequestHandler requestHandler;
   private FhirContext myCtx;

   private IParser jparser;

   /**
    * Constructor using a specific logger
    */
   public GFEInterceptor(FhirContext ctx, String serverAddress) {
       requestHandler = new RequestHandler();
       if (serverAddress != null && !serverAddress.equals("")) {
         baseUrl = serverAddress;
       }
       myCtx = ctx;
       client = myCtx.newRestfulGenericClient(baseUrl + "/fhir");
       jparser = myCtx.newJsonParser();
       jparser.setPrettyPrint(true);

   }

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
     // Here is where the Claim should be evaluated
     System.out.println("Intercepted the request");
     if (theRequest.getMethod().equals("POST") && parts.length > 3 && parts[2].equals("Claim") && parts[3].equals("$gfe-submit")) {
         System.out.println("Received Submit");
         try {
            handleSubmit(theRequest, theResponse);
         } catch (Exception e) {
            myLogger.info("Error in submission");
            myLogger.info(e.getMessage());
            e.printStackTrace();
         }
         return false;
     }
     return true;
  }
  /**
   * Create a new bundle with type Collection and an identifier to persist and be used for queries
   * @return the new bundle
   */
  public Bundle createBundle() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    Identifier identifier = new Identifier();
    identifier.setSystem("http://example.org/documentIDs");
    String uuid = UUID.randomUUID().toString();
    identifier.setValue(uuid);
    bundle.setIdentifier(identifier);
    MethodOutcome outcome = client.create().resource(bundle).prettyPrint().encodedJson().execute();
    if (outcome.getCreated()) {
        bundle = (Bundle) outcome.getResource();
    }
    return bundle;
  }
  /**
   * Create new resource in server that contains the aeob
   * @param  aeob the aeob to create in the server
   * @return      the result which has the new ID
   */
  public ExplanationOfBenefit createAEOB(ExplanationOfBenefit aeob) {
      MethodOutcome outcome = client.create().resource(aeob).prettyPrint().encodedJson().execute();
      if (outcome.getCreated()) {
          aeob = (ExplanationOfBenefit) outcome.getResource();
      }
      return aeob;
  }
  /**
   * Update the bundle
   * @param bundle the bundle to update
   */
  public void updateBundle(Bundle bundle) {
      try {
          MethodOutcome outcome = client.update().resource(bundle).prettyPrint().encodedJson().execute();
      } catch(Exception e) {
          System.out.println("Failure to update the bundle");
      }
  }
  /**
    * Parse the request and return the body data
    * @param  r the request
    * @return   the data from the request
    */
   public String parseRequest(HttpServletRequest r) {
      String targetString = "";
      try {
        Reader initialReader =  r.getReader();
        char[] arr = new char[8 * 1024];
        StringBuilder buffer = new StringBuilder();
        int numCharsRead;
        int count = 0;
        while ((numCharsRead = initialReader.read(arr, 0, arr.length)) != -1) {
            buffer.append(arr, 0, numCharsRead);
        }
        initialReader.close();
        targetString = buffer.toString();

      } catch (Exception e) { System.out.println("Found Exception" + e.getMessage());/*report an error*/ }
      return targetString;
  }
  /**
   * Modify the aeob with new extensions and all the resources from the gfe to the aeob and add all
   * the resources to the aeobBundle
   * @param  gfeBundle  the gfe bundle
   * @param  aeob       the aeob to modify
   * @param  aeobBundle the bundle to return
   * @return            the complete aeob bundle
   */
  public Bundle convertGFEtoAEOB(Bundle gfeBundle, ExplanationOfBenefit aeob, Bundle aeobBundle) {
    List<Extension> gfeExts = new ArrayList<>();
    Extension gfeReference = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeReference");
    gfeExts.add(gfeReference);
    Extension disclaimer = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/disclaimer", new StringType("Estimate Only ..."));
    gfeExts.add(disclaimer);
    Extension expirationDate = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/expirationDate", DateTimeType.now());
    gfeExts.add(expirationDate);
    Claim claim = (Claim) gfeBundle.getEntry().get(0).getResource();
    gfeReference.setValue(new Reference("Claim/" + claim.getId()));
    if (claim.getMeta().getProfile().get(0).equals("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/pct-gfe-Institutional")) {
        convertInstitutional(claim, gfeBundle, aeob, aeobBundle);
    } else {
        convertProfessional(claim, gfeBundle, aeob, aeobBundle);
    }
    Bundle.BundleEntryComponent temp = new Bundle.BundleEntryComponent();
    aeob = createAEOB(aeob);
    temp.setFullUrl("http://example.org/fhir/ExplanationOfBenefit/" + aeob.getId());
    temp.setResource(aeob);
    aeobBundle.addEntry(temp);
    return aeobBundle;
  }
  /**
   * Add all resources into the new aeob bundle and update the aeob with the institutional
   * @param  claim      the claim for the aeob
   * @param  gfeBundle  the bundle with all gfe resources
   * @param  aeob       the aeob
   * @param  aeobBundle the return bundle to be updated
   * @return            the new bundle
   */
  public Bundle convertInstitutional(Claim claim, Bundle gfeBundle, ExplanationOfBenefit aeob, Bundle aeobBundle) {
      CodeableConcept type = new CodeableConcept();
      List<Coding> c = new ArrayList<>();
      Coding cd = new Coding("http://terminology.hl7.org/CodeSystem/claim-type", "institutional", "Institutional");
      c.add(cd);
      type.setCoding(c);
      aeob.setSubType(type);
      for (Bundle.BundleEntryComponent e: gfeBundle.getEntry()) {
          IBaseResource bundleEntry = (IBaseResource) e.getResource();
          String resource = jparser.encodeResourceToString(bundleEntry);
          if (bundleEntry.fhirType().equals("Patient")) {
              Patient patient = (Patient) bundleEntry;
              aeob.setPatient(new Reference(patient.getId()));
          } else if (bundleEntry.fhirType().equals("Organization")) {
              // Update if institutional or professional
              Organization org = (Organization) bundleEntry;
              if (org.getType().get(0).getCoding().get(0).getCode().equals("pay")) {
                aeob.setInsurer(new Reference(org.getId()));
              } else if (org.getType().get(0).getCoding().get(0).getCode().equals("prov")){
                aeob.setProvider(new Reference(org.getId()));
              } else if (org.getType().get(0).getCoding().get(0).getCode().equals("Institutional-submitter")) {
                aeob.setProvider(new Reference(org.getId()));
              }

          } else if (bundleEntry.fhirType().equals("Coverage")) {
              Coverage cov = (Coverage) bundleEntry;
              aeob.getInsurance().get(0).setCoverage(new Reference(cov.getId()));
          }
          aeobBundle.addEntry(e);
      }

      return aeobBundle;
  }
  /**
   * Add all resources into the new aeob bundle and update the aeob with the professional
   * @param  claim      the claim for the aeob
   * @param  gfeBundle  the bundle with all gfe resources
   * @param  aeob       the aeob
   * @param  aeobBundle the return bundle to be updated
   * @return            the new bundle
   */
  public Bundle convertProfessional(Claim claim, Bundle gfeBundle, ExplanationOfBenefit aeob, Bundle aeobBundle) {
     CodeableConcept type = new CodeableConcept();
     List<Coding> c = new ArrayList<>();
     Coding cd = new Coding("http://terminology.hl7.org/CodeSystem/claim-type", "professional", "Professional");
     c.add(cd);
     type.setCoding(c);
     aeob.setSubType(type);
      for (Bundle.BundleEntryComponent e: gfeBundle.getEntry()) {
          IBaseResource bundleEntry = (IBaseResource) e.getResource();
          String resource = jparser.encodeResourceToString(bundleEntry);
          if (bundleEntry.fhirType().equals("Patient")) {
              Patient patient = (Patient) bundleEntry;
              aeob.setPatient(new Reference(patient.getId()));
          } else if (bundleEntry.fhirType().equals("Practitioner")){
              Practitioner prac = (Practitioner) bundleEntry;
              if (prac.getMeta().getProfile().get(0).equals("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-practitioner")) {
                aeob.setProvider(new Reference(prac.getId()));
              }
          } else if (bundleEntry.fhirType().equals("Organization")) {
              // Update if institutional or professional
              Organization org = (Organization) bundleEntry;
              if (org.getType().get(0).getCoding().get(0).getCode().equals("pay")) {
                aeob.setInsurer(new Reference(org.getId()));
              } else if (org.getType().get(0).getCoding().get(0).getCode().equals("prov")) {
                // Provider
                aeob.setProvider(new Reference(org.getId()));
              }
          } else if (bundleEntry.fhirType().equals("Coverage")) {
              Coverage cov = (Coverage) bundleEntry;
              aeob.getInsurance().get(0).setCoverage(new Reference(cov.getId()));
          }
          aeobBundle.addEntry(e);
      }

      return aeobBundle;
  }
  /**
   * Parse the resource and create the new aeob bundle. Send the initial bundle in the return
   * @param  theRequest  the request with the resource
   * @param  theResponse the response
   * @throws Exception   any errors
   */
  public void handleSubmit(HttpServletRequest theRequest, HttpServletResponse theResponse) throws Exception {
      theResponse.setStatus(404);
      theResponse.setContentType("application/json");
      theResponse.setCharacterEncoding("UTF-8");
      theResponse.addHeader("Access-Control-Allow-Origin", "*");
      theResponse.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
      theResponse.addHeader("Access-Control-Allow-Headers", "X-Requested-With,Origin,Content-Type, Accept, Authorization");
      String outputString = "";
      try {
        Bundle returnBundle = createBundle();
        // Convert the original bundle to a string. This will allow a query to occur later
        outputString = jparser.encodeResourceToString((IBaseResource)returnBundle);

        String resource = parseRequest(theRequest);
        String eob = FileLoader.loadResource("raw-aeob.json");

        ExplanationOfBenefit aeob = jparser.parseResource(ExplanationOfBenefit.class, eob);
        Bundle gfeBundle = jparser.parseResource(Bundle.class, resource);
        convertGFEtoAEOB(gfeBundle, aeob, returnBundle);

        String result = jparser.encodeResourceToString((IBaseResource)returnBundle);
        System.out.println("\n\n\n--------------------------------------------------------");
        System.out.println("Final Result: \n" + result);
        System.out.println("--------------------------------------------------------\n\n\n");
        updateBundle(returnBundle);

        System.out.println(outputString);
        theResponse.setStatus(200);
      } catch(Exception ex) {
          OperationOutcome oo = new OperationOutcome();
          OperationOutcome.OperationOutcomeIssueComponent ooic = new OperationOutcome.OperationOutcomeIssueComponent();
          ooic.setSeverity(OperationOutcome.IssueSeverity.ERROR);
          ooic.setCode(OperationOutcome.IssueType.EXCEPTION);
          CodeableConcept cc = new CodeableConcept();
          cc.setText(ex.getMessage());
          ooic.setDetails(cc);
          oo.addIssue(ooic);
          outputString = jparser.encodeResourceToString((IBaseResource) oo);
      }
      PrintWriter out = theResponse.getWriter();
      out.print(outputString);
      out.flush();
  }

}
