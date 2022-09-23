package ca.uhn.fhir.jpa.starter.interceptors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hl7.fhir.r4.model.Contract;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.utils.FileLoader;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;

/**
 * Class for intercepting and handling the subsciptions
 */
@Interceptor
public class DataInterceptor {
   private final Logger myLogger = LoggerFactory.getLogger(DataInterceptor.class.getName());

   private String baseUrl = "http://localhost:8080";//"https://davinci-pct-ehr.logicahealth.org";//"http://localhost:8080";//

   private IGenericClient client; //
   private boolean dataLoaded;
   private FhirContext myCtx;

   private IParser jparser;
  private final String[] organizations = {
      "ri_resources/a_resources/Organization-org1001.json",
  };
  private final String[] patients = {
      "ri_resources/a_resources/Patient-patient1001.json"
  };
  private final String[] contracts = {
      "ri_resources/a_resources/Contract-contract1001.json"
  };
  private final String[] coverages = {
      "ri_resources/a_resources/Coverage-coverage1001.json"
  };
   /**
    * Constructor using a specific logger
    */
   public DataInterceptor(FhirContext ctx, String serverAddress) {
       dataLoaded = false;
       if (serverAddress != null && !serverAddress.equals("")) {
         baseUrl = serverAddress;
       }
       myCtx = ctx;
       client = myCtx.newRestfulGenericClient(baseUrl + "/fhir");
       jparser = myCtx.newJsonParser();
       jparser.setPrettyPrint(true);

   }
   /**
    *
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
      if (!dataLoaded) {
         dataLoaded = true;
         myLogger.info("First request made to Server");
         myLogger.info("Loading all data");
         for (String filename: organizations) {
            loadDataOrganization(filename);
         }
         myLogger.info("Loaded Organizations");
         for (String filename: patients) {
            loadDataPatient(filename);
         }
         myLogger.info("Loaded Patients");
         for (String filename: contracts) {
            loadDataContract(filename);
         }
         myLogger.info("Loaded Contracts");
         for (String filename: coverages) {
            loadDataCoverage(filename);
         }
         myLogger.info("Loaded Coverage");
      }
      return true;
  }
  public void loadDataOrganization(String resource) {
      try {
          String p = FileLoader.loadResource(resource);
          Organization r = jparser.parseResource(Organization.class, p);
          myLogger.info("Uploading resource: {} ", resource);
          MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
      } catch(Exception e) {
          myLogger.info(e.toString());
          myLogger.info("Failure to update the Organization");
      }
  }
  public void loadDataPatient(String resource) {
      try {
          String p = FileLoader.loadResource(resource);
          Patient r = jparser.parseResource(Patient.class, p);
          myLogger.info("Uploading resource " + resource);
          MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
      } catch(Exception e) {
          myLogger.info("Failure to update the Patient");
      }
  }
  public void loadDataContract(String resource) {
      try {
          String p = FileLoader.loadResource(resource);
          Contract r = jparser.parseResource(Contract.class, p);
          myLogger.info("Uploading resource " + resource);
          MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
      } catch(Exception e) {
          myLogger.info("Failure to update the Contract");
      }
  }
  public void loadDataCoverage(String resource) {
      try {
          String p = FileLoader.loadResource(resource);
          Coverage r = jparser.parseResource(Coverage.class, p);
          myLogger.info("Uploading resource " + resource);
          MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
      } catch(Exception e) {
          myLogger.info("Failure to update the Coverage");
      }
  }

}
