package com.lantanagroup.common;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import com.apicatalog.jsonld.loader.FileLoader;
import com.lantanagroup.servers.davincipct.DavinciPctProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.r4.model.*;

import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.param.*;
import ca.uhn.fhir.rest.api.server.IBundleProvider;

// TODO, no need to use client for updating. Codnsider moving to Dao transactions.
// Also switch over to a common framework for preloading resources.

@Interceptor
public class ProcessCustomizer {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ProcessCustomizer.class);

    @Autowired
    protected DavinciPctProperties serverProperties;

    protected FhirContext fhirContext;
    protected DaoRegistry theDaoRegistry;
    protected String key;
    private IGenericClient client;
    private boolean dataLoaded;

    private IParser jparser;
    private final String[] organizations = {
            "ri_resources/Organization-org1001.json",
    };
    private final String[] patients = {
            "ri_resources/Patient-patient1001.json"
    };
    private final String[] contracts = {
            "ri_resources/Contract-contract1001.json"
    };
    private final String[] coverages = {
            "ri_resources/Coverage-coverage1001.json"
    };

    public ProcessCustomizer(FhirContext fhirContext, DaoRegistry theDaoRegistry, String key) {
        dataLoaded = false;
        this.fhirContext = fhirContext;
        this.theDaoRegistry = theDaoRegistry;
        this.key = key;
        client = null;

        jparser = fhirContext.newJsonParser();
        jparser.setPrettyPrint(true);

    }

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
   public boolean incomingRequestPreProcessed(RequestDetails theRequestDetails, HttpServletRequest theRequest, HttpServletResponse theResponse) {
      //String[] parts = theRequest.getRequestURI().toString().split("/");
      // Here is where the Claim should be evaluated
      if (!dataLoaded) {
        
        //client = fhirContext.newRestfulGenericClient(theRequestDetails.getFhirServerBase() + "/fhir");
        //String test = theRequest.getScheme() + "://" + theRequest.getServerName() + ":" + theRequest.getServerPort() + theRequest.getContextPath();
        client = fhirContext.newRestfulGenericClient(theRequest.getScheme() + "://" + theRequest.getServerName() + ":" + theRequest.getServerPort() + "/fhir");
        dataLoaded = true;
        logger.info("First request made to Server");
        logger.info("Loading all data");
        for (String filename: organizations) {
            loadDataOrganization(filename);
        }
        logger.info("Loaded Organizations");
        for (String filename: patients) {
           loadDataPatient(filename);
        }
        logger.info("Loaded Patients");
        for (String filename: contracts) {
           loadDataContract(filename);
        }
        logger.info("Loaded Contracts");
        for (String filename: coverages) {
           loadDataCoverage(filename);
        }
        logger.info("Loaded Coverage");
      }
      return true;
  }

    public void loadDataOrganization(String resource) {
        try {
            String p = util.loadResource(resource);
            Organization r = jparser.parseResource(Organization.class, p);
            logger.info("Uploading resource: {} ", resource);
            MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
        } catch (Exception e) {
            logger.info(e.toString());
            logger.info("Failure to update the Organization");
        }
    }

    public void loadDataPatient(String resource) {
        try {
            String p = util.loadResource(resource);
            Patient r = jparser.parseResource(Patient.class, p);
            logger.info("Uploading resource " + resource);
            MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
        } catch (Exception e) {
            logger.info("Failure to update the Patient");
        }
    }

    public void loadDataContract(String resource) {
        try {
            String p = util.loadResource(resource);
            Contract r = jparser.parseResource(Contract.class, p);
            logger.info("Uploading resource " + resource);
            MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
        } catch (Exception e) {
            logger.info("Failure to update the Contract");
        }
    }

    public void loadDataCoverage(String resource) {
        try {
            String p = util.loadResource(resource);
            Coverage r = jparser.parseResource(Coverage.class, p);
            logger.info("Uploading resource " + resource);
            MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
        } catch (Exception e) {
            logger.info("Failure to update the Coverage");
        }
    }


}
