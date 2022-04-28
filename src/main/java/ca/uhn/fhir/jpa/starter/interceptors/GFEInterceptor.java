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
   @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
   public boolean incomingRequestPostProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) {
     String[] parts = theRequest.getRequestURI().toString().split("/");
     // Here is where the Claim should be evaluated
     System.out.println("Intercepted the request");
     if (theRequest.getMethod().equals("POST") && parts.length > 3 && parts[2].equals("Claim") && parts[3].equals("$gfe-submit")) {
         System.out.println("Received GFE Submit");
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
    bundle.setTimestamp(new Date());
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
   * Update the AEOB
   * @param aeob the aeob to update
   */
  public void updateAEOB(ExplanationOfBenefit aeob) {
      try {
          MethodOutcome outcome = client.update().resource(aeob).prettyPrint().encodedJson().execute();
      } catch(Exception e) {
          System.out.println("Failure to update the aeob");
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
    // NOTE: Add processing for revenue
    // total[0].amount.value adjudication[0].amount.value net.value
    //

    List<Extension> gfeExts = new ArrayList<>();
    Extension gfeReference = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeReference");
    gfeExts.add(gfeReference);
    Extension disclaimer = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/disclaimer", new StringType("Estimate Only ..."));
    gfeExts.add(disclaimer);
    // TODO: make expirationDate dynamic
    Extension expirationDate = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/expirationDate", new DateType(2022, 6, 15));
    gfeExts.add(expirationDate);
    Claim claim = (Claim) gfeBundle.getEntry().get(0).getResource();

    aeob.setExtension(gfeExts);

    // Update the AEOB resource based on the claim. NOTE: additional work might need to be done here
    // This just assumes that the numbers are the same to the total claim
    aeob.getTotal().get(0).setAmount(claim.getTotal());
    aeob.getItem().get(0).getAdjudication().get(0).setAmount(claim.getTotal());
    aeob.getItem().get(0).setNet(claim.getTotal());


    gfeReference.setValue(new Reference(gfeBundle));
    Bundle.BundleEntryComponent temp = new Bundle.BundleEntryComponent();

    aeob = createAEOB(aeob);
    temp.setFullUrl("http://example.org/fhir/ExplanationOfBenefit/" + aeob.getId().split("/_history")[0]);
    temp.setResource(aeob);
    aeobBundle.addEntry(temp);
    if (claim.getMeta().getProfile().get(0).equals("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/pct-gfe-Institutional")) {
        convertInstitutional(claim, gfeBundle, aeob, aeobBundle);
    } else {
        convertProfessional(claim, gfeBundle, aeob, aeobBundle);
    }
    updateAEOB(aeob);
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
      aeob.getType().getCoding().get(0).setCode("institutional");
      aeob.getType().getCoding().get(0).setDisplay("Institutional");
      System.out.println("Processing Institutional Claim");
      for (Bundle.BundleEntryComponent e: gfeBundle.getEntry()) {
          IBaseResource bundleEntry = (IBaseResource) e.getResource();
          String resource = jparser.encodeResourceToString(bundleEntry);
          if (bundleEntry.fhirType().equals("Patient")) {
              Patient patient = (Patient) bundleEntry;
              aeob.setPatient(new Reference(patient.getId()));
          } else if (bundleEntry.fhirType().equals("Organization")) {
              Organization org = (Organization) bundleEntry;
              if (org.getType().get(0).getCoding().get(0).getCode().equals("pay")) {
                aeob.setInsurer(new Reference(org.getId()));
              } else if (org.getType().get(0).getCoding().get(0).getCode().equals("prov")){
                aeob.setProvider(new Reference(org.getId()));
              } else if (org.getType().get(0).getCoding().get(0).getCode().equals("institutional-submitter")) {
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
      aeob.getType().getCoding().get(0).setCode("professional");
      aeob.getType().getCoding().get(0).setDisplay("Professional");
      System.out.println("Processing Professional Claim");
      for (Bundle.BundleEntryComponent e: gfeBundle.getEntry()) {
          IBaseResource bundleEntry = (IBaseResource) e.getResource();
          String resource = jparser.encodeResourceToString(bundleEntry);
          System.out.println(bundleEntry.fhirType());
          if (bundleEntry.fhirType().equals("Patient")) {
              Patient patient = (Patient) bundleEntry;
              aeob.setPatient(new Reference(patient.getId()));
          } else if (bundleEntry.fhirType().equals("Organization")) {
              Organization org = (Organization) bundleEntry;
              if (org.getType().get(0).getCoding().get(0).getCode().equals("pay")) {
                aeob.setInsurer(new Reference(org.getId()));
              } else if (org.getType().get(0).getCoding().get(0).getCode().equals("prov") && claim.getProvider().getReference().contains("Organization")) {
                // Provider
                System.out.println("Adding Provider with Organization");
                aeob.setProvider(new Reference(org.getId()));
              }
          } else if (bundleEntry.fhirType().equals("Coverage")) {
              Coverage cov = (Coverage) bundleEntry;
              aeob.getInsurance().get(0).setCoverage(new Reference(cov.getId()));
          } else if (bundleEntry.fhirType().equals("PractitionerRole") && claim.getProvider().getReference().contains("PractitionerRole")) {
              PractitionerRole pr = (PractitionerRole) bundleEntry;
              System.out.println("Adding Provider by PractitionerRole");
              aeob.setProvider(new Reference(pr.getId()));
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
        aeob.setCreated(new Date());
        Bundle gfeBundle = jparser.parseResource(Bundle.class, resource);
        convertGFEtoAEOB(gfeBundle, aeob, returnBundle);

        String result = jparser.encodeResourceToString((IBaseResource)returnBundle);
        System.out.println("\n\n\n--------------------------------------------------------");
        // System.out.println("Final Result: \n" + result);
        System.out.println("--------------------------------------------------------\n\n\n");
        updateBundle(returnBundle);

        // System.out.println(outputString);
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
