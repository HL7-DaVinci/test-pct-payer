package ca.uhn.fhir.jpa.starter.resourceProvider;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;
import java.util.UUID;


import java.util.Map;
import java.util.HashMap;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;

import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.BenefitBalanceComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.BenefitComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.NoteComponent;
import org.hl7.fhir.r4.model.codesystems.ExplanationofbenefitStatus;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Money;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.UrlType;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryResponseComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.utils.FileLoader;
import ca.uhn.fhir.jpa.starter.utils.RequestHandler;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.IResourceProvider;

/**
 * Class for processing the gfe-submit operation
 */
public class GFESubmitProvider implements IResourceProvider {
  private static final String SERVICE_DESCRIPTION_EXTENSION = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/serviceDescription";
  private static final String DATA_ABSENT_REASON_EXTENSION = "http://hl7.org/fhir/StructureDefinition/data-absent-reason";

private final Logger myLogger = LoggerFactory.getLogger(GFESubmitProvider.class.getName());

  private String baseUrl = "https://pct-payer.davinci.hl7.org";

  private IGenericClient client;
  private RequestHandler requestHandler;
  private FhirContext myCtx;

  private IParser jparser;
  private IParser xparser;
  private Random rand;

  @Autowired
  AppProperties appProperties;

  private Integer simulatedDelaySeconds = 10;

  @Override
  public Class<Claim> getResourceType() {
    return Claim.class;
  }

  /**
   * Constructor using a specific logger
   */
  public GFESubmitProvider(FhirContext ctx, String serverAddress) {
    requestHandler = new RequestHandler();
    if (serverAddress != null && !serverAddress.equals("")) {
      baseUrl = serverAddress;
    }
    myCtx = ctx;
    client = myCtx.newRestfulGenericClient(baseUrl + "/fhir");
    jparser = myCtx.newJsonParser();
    jparser.setPrettyPrint(true);
    xparser = myCtx.newXmlParser();
    xparser.setPrettyPrint(true);
    rand = new Random();
  }

  /**
   * Set the base url
   * 
   * @param url the url
   */
  public void setBaseUrl(String url) {
    baseUrl = url;
  }  

  @Operation(name = "$gfe-submit", manualResponse = true, manualRequest = true)
  public void gfeSubmit(HttpServletRequest theRequest, HttpServletResponse theResponse) throws IOException {
    myLogger.info("Received GFE Submit");

    try {
      handleSubmit(theRequest, theResponse);
    } catch (Exception e) {
      myLogger.info("Error in submission");
      myLogger.info(e.getMessage());
      e.printStackTrace();
    }
  }


  @Operation(name = "$gfe-submit-poll-status", manualResponse = true, manualRequest = true, idempotent = true)
  public void getPollStatus(HttpServletRequest theRequest, HttpServletResponse theResponse, @OperationParam(name="_bundleId") String bundleId) throws IOException {

    theResponse.setHeader("Access-Control-Allow-Origin", "*");

    // Attempt to fetch bundle
    Bundle bundle = client.read().resource(Bundle.class).withId(bundleId).execute();

    if (bundle == null) {
      theResponse.setStatus(404);
      return;
    }
    
    // Simulate a bundle building delay for testing
    // If bundle was created less than the set number of simluated delay seconds we'll return the "in progress" 202 Accepted status
    if (Instant.now().isBefore(bundle.getTimestamp().toInstant().plusSeconds(simulatedDelaySeconds))) {
      theResponse.setStatus(202);
      theResponse.setHeader("Access-Control-Expose-Headers", "Retry-After");
      theResponse.setHeader("Retry-After", simulatedDelaySeconds.toString());
      return;
    }


    // Check for special return cases based on patient last name

    for (BundleEntryComponent entry : bundle.getEntry()) {

      Resource res = entry.getResource();
      if (res.fhirType().equals("Patient")) {

        String familyName = "";
        for (HumanName humanName : ((Patient) res).getName()) {
          familyName = humanName.getFamily().toLowerCase();
          myLogger.info("Found patient family name: {}", familyName);

          // Patient last name of "Coffee" will return adjudication error response
          if (familyName.toLowerCase().equals("coffee")) {
            adjudicationErrorResponse(theRequest, theResponse, bundle);
            return;
          }

          // Patient last name of "Private" will return "PCT AEOB Complete" response instead of bundle
          if (familyName.toLowerCase().equals("private")) {
            aeobCompleteResponse(theRequest, theResponse, bundle);
            return;
          }

        }
      }

    }


    Bundle responseBundle = new Bundle();
    responseBundle.setType(BundleType.BATCHRESPONSE);

    BundleEntryComponent bundleEntry = new BundleEntryComponent();
    BundleEntryResponseComponent bundleEntryResponse = new BundleEntryResponseComponent();
    bundleEntryResponse.setStatus("200 OK");
    bundleEntryResponse.setLocation(String.format("Bundle/%s", bundleId));
    bundleEntry.setResponse(bundleEntryResponse);
    bundleEntry.setResource(bundle);
    responseBundle.addEntry(bundleEntry);
    

    try {      
      String contentType = theRequest.getHeader("Content-Type");
      String accept = theRequest.getHeader("Accept");
      myLogger.info("Content-Type: " + contentType);
      myLogger.info("Accept: " + accept);

      String outputString = getOutputString(theRequest, theResponse, responseBundle);

      theResponse.setStatus(200);
      theResponse.getWriter().write(outputString);
      theResponse.getWriter().close();

    } catch (Exception e) {
      handleError(theRequest, theResponse, e);
    }


  }

  public void adjudicationErrorResponse(HttpServletRequest theRequest, HttpServletResponse theResponse, Bundle bundle) throws IOException  {

    myLogger.info("Sending adjudication error response.");

    String adjudicationError = FileLoader.loadResource("raw-adjudication-error.json");
    OperationOutcome oo = jparser.parseResource(OperationOutcome.class, adjudicationError);

    oo.setId("PCT-AEOB-Adjudication-Error-" + bundle.getIdElement().getIdPart());
    oo.getIssueFirstRep().setDiagnostics("Some adjudication error for bundle " + bundle.getIdElement().getIdPart());

    String outputString = getOutputString(theRequest, theResponse, oo);


    try {
      theResponse.setStatus(418);
      theResponse.getWriter().write(outputString);
    } catch (Exception e) {
      handleError(theRequest, theResponse, e);
    }

    theResponse.getWriter().close();

  }


  public void aeobCompleteResponse(HttpServletRequest theRequest, HttpServletResponse theResponse, Bundle bundle) throws IOException {

    myLogger.info("Sending AEOB complete response.");

    String aeobComplete = FileLoader.loadResource("raw-aeob-complete.json");
    OperationOutcome oo = jparser.parseResource(OperationOutcome.class, aeobComplete);

    oo.setId("PCT-AEOB-Complete-Example-" + bundle.getIdElement().getIdPart());
    oo.getIssueFirstRep().setDiagnostics("AEOB processing for bundle " + bundle.getIdElement().getIdPart() + " is complete, the AEOB will be sent directly to the patient. No AEOB will be returned to the submitter.");

    String outputString = getOutputString(theRequest, theResponse, oo);

    try {
      theResponse.setStatus(418);
      theResponse.getWriter().write(outputString);
    } catch (Exception e) {
      handleError(theRequest, theResponse, e);
    }

    theResponse.getWriter().close();


  }


  /**
   * Create a new bundle with type Collection and an identifier to persist and be
   * used for queries
   * 
   * @return the new bundle
   */
  public Bundle createBundle() {
    Bundle bundle = new Bundle();
    bundle.setType(BundleType.COLLECTION);
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
   * 
   * @param aeob the aeob to create in the server
   * @return the result which has the new ID
   */
  public ExplanationOfBenefit saveAeob(ExplanationOfBenefit aeob) {
	  try {
		    MethodOutcome outcome = client.create().resource(aeob).prettyPrint().encodedJson().execute();
		    if (outcome.getCreated()) {
		      aeob = (ExplanationOfBenefit) outcome.getResource();
		    }
	  } catch (Exception e) {
		  myLogger.info("Unable to create resource: " + e.getMessage());
	  }
    return aeob;
  }
  
  public IBaseResource createResource(IBaseResource r) {
	  try {
		  MethodOutcome outcome = client.create().resource(r).prettyPrint().encodedJson().execute();
		  if (outcome.getCreated()) {
			  r = outcome.getResource();
		  }
	  } catch (Exception e) {
		  myLogger.info("Unable to create resource: " + e.getMessage());
	  }
	  return r;
  }

  /**
   * Update the bundle
   * 
   * @param bundle the bundle to update
   */
  public void updateBundle(Bundle bundle) {
      try {
          MethodOutcome outcome = client.update().resource(bundle).prettyPrint().encodedJson().execute();
      } catch(Exception e) {
          myLogger.info("Failure to update the bundle");
    }
  }
  
  public IBaseResource updateResource(IBaseResource r) {
      try {
          MethodOutcome outcome = client.update().resource(r).prettyPrint().encodedJson().execute();
      } catch(Exception e) {
          myLogger.info("Failure to update the bundle");
    }
      return r;
  }


  /**
   * Update the AEOB
   * 
   * @param aeob the aeob to update
   */
  public void updateAEOB(ExplanationOfBenefit aeob) {
    try {
      MethodOutcome outcome = client.update().resource(aeob).prettyPrint().encodedJson().execute();
    } catch (Exception e) {
      myLogger.info("Failure to update the aeob: " + e.getMessage());
    }
  }

  /**
   * Parse the request and return the body data
   * 
   * @param r the request
   * @return the data from the request
   */
  public String parseRequest(HttpServletRequest r) {
    String targetString = "";
    try {
      Reader initialReader = r.getReader();
      
      char[] arr = new char[8 * 1024];
      StringBuilder buffer = new StringBuilder();
      int numCharsRead;
      int count = 0;
      while ((numCharsRead = initialReader.read(arr, 0, arr.length)) != -1) {
        buffer.append(arr, 0, numCharsRead);
      }
      initialReader.close();
      targetString = buffer.toString();
      

    } catch (Exception e) {
      myLogger.info("Found Exception: {}", e.getMessage());/* report an error */
    }
    return targetString;
  }

  /**
   * Modify the aeob with new extensions and all the resources from the gfe to the
   * aeob and add all
   * the resources to the aeobBundle
   * 
   * @param gfeBundle  the gfe bundle
   * @param aeob       the aeob to modify
   * @param aeobBundle the bundle to return
   * @return the complete aeob bundle
   */
  public Bundle convertGFEtoAEOB(Bundle gfeBundle, Claim claim, ExplanationOfBenefit aeob, Bundle aeobBundle) {
    myLogger.info("Converting GFE to AEOB");
    try {

	    Bundle.BundleEntryComponent providerEntry = addProviderReference(gfeBundle, claim, aeob);
	    
	    addExtensions(gfeBundle, claim, providerEntry, aeob);
	    
	    
	    
	    addBenefitPeriod(aeob);

	    addProcessNote(aeob);
	    addUniqueClaimIdentifier(aeob);
	    
	    addClaimIdentifierReference(claim, aeob);

	    double eligibleAmountPercent = (100.0 - rand.nextInt(21)) / 100.0;
	    
	    double cost = addItems(claim, aeob, eligibleAmountPercent);

	    Money eligibleAmount = new Money();
	    // Totals
	
	    List<ExplanationOfBenefit.TotalComponent> eobTotals = addTotals(claim, aeob, eligibleAmountPercent, cost,
				eligibleAmount);
	
	    // Submitted
	    addSubmitted(claim, eobTotals);
	
	    // Eligible
	    addEligible(eligibleAmount, eobTotals);
	    aeob.setTotal(eobTotals);
		  addBenefitBalance(aeob);
	    
	    myLogger.info("Saving AEOB");
	    	aeob = saveAeob(aeob);
	    addAeobToBundle(aeob, aeobBundle);
	    
      // THis is unnecessary because this function gets called for each claim. This call would add a GFE bundle for each claim in the GFEBundle.
	    //addGfeBundleToAeobBundle(gfeBundle, aeobBundle);
	    
	    for (Extension ex : claim.getExtensionsByUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeProviderAssignedIdentifier")) {
	      aeob.addExtension(ex);
	    }
	    if (claim.getMeta().getProfile().get(0)
	        .equals("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/pct-gfe-Institutional")) {
	      convertInstitutional(claim, gfeBundle, aeob);
	    } else {
	      convertProfessional(claim, gfeBundle, aeob);
	    }
	    
	    myLogger.info("Updating AEOB");
	    updateAEOB(aeob);
    } catch (Exception e) {
    		myLogger.info("Error: " + e.getMessage());
    }
    return aeobBundle;
  }

  private void addGfeBundleToAeobBundle(Bundle gfeBundle, Bundle aeobBundle) {
    myLogger.info("Adding GFE Bundle to AEOB Bundle");
    Bundle.BundleEntryComponent gfeBundleEntry = new Bundle.BundleEntryComponent();
    gfeBundleEntry.setFullUrl("http://example.org/fhir/Bundle/" + gfeBundle.getId());
    gfeBundleEntry.setResource(gfeBundle);

    if(!gfeBundle.hasMeta())
    {
      Meta gfeBundle_meta = new Meta();
      gfeBundle_meta.setVersionId("1");
      gfeBundle_meta.setLastUpdated(new Date());
      gfeBundle_meta.addProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-bundle");
      gfeBundle.setMeta(gfeBundle_meta);
    }
    else{
      Meta gfeBundle_meta = gfeBundle.getMeta();
      if(!gfeBundle_meta.hasProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-bundle")){
        gfeBundle_meta.addProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-bundle");
      }
    }
    aeobBundle.addEntry(gfeBundleEntry);
  }

  private void addAeobToBundle(ExplanationOfBenefit aeob, Bundle aeobBundle) {
    myLogger.info("Adding AEOB to AEOB Bundle");
    Bundle.BundleEntryComponent aeobEntry = new Bundle.BundleEntryComponent();

    aeobEntry.setFullUrl("http://example.org/fhir/ExplanationOfBenefit/" + aeob.getIdElement().getIdPart());
    aeobEntry.setResource(aeob);
    aeobBundle.addEntry(aeobEntry);
  }

  /**
   * Add AEOB Summary EOB to the AOEB Bundle
   * 
   * @param aeobBundle the bundle to add to summary to
   * @return the complete aeob bundle
   */
  public Bundle AddAEOBSummarytoAEOBBundle(Bundle gfeBundle, Bundle aeobBundle) {
    myLogger.info("Summarizing AEOB Bundle");
    try {
      ExplanationOfBenefit aeob_summary = new ExplanationOfBenefit();
      Meta aeob_summary_meta = new Meta();
      aeob_summary_meta.setVersionId("1");
      aeob_summary_meta.setLastUpdated(new Date());
      aeob_summary_meta.addProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-summary");
      aeob_summary.setMeta(aeob_summary_meta);
      aeob_summary.setId("PCT-AEOB-Summary");

      Extension outOfNetworkProviderInfo = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/inNetworkProviderOptionsLink");
	    outOfNetworkProviderInfo.setValue(new UrlType("http://example.com/out-of-networks.html"));
      aeob_summary.addExtension(outOfNetworkProviderInfo);

      Extension serviceExtension = new Extension(SERVICE_DESCRIPTION_EXTENSION);
      serviceExtension.setValue(new StringType("Example service - Should this really be required for a summary? How would the payer summarize a service description across all EOBs?"));
      
      aeob_summary.setStatus(ExplanationOfBenefitStatus.ACTIVE);
      CodeableConcept eobType = new CodeableConcept();
      Coding c = new Coding();
      c.setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAEOBTypeSummaryCS");
      c.setCode("eob-summary");
      c.setDisplay("Explanation of Benefit Summary");
      eobType.getCoding().add(c);
      aeob_summary.setType(eobType);

      aeob_summary.setUse(ExplanationOfBenefit.Use.PREDETERMINATION);

      aeob_summary.setCreated(aeob_summary_meta.getLastUpdated());

      Extension providerAbsentReason = new Extension(DATA_ABSENT_REASON_EXTENSION);
      Coding darCoding = new Coding();
	    darCoding.setCode("not-applicable");
      providerAbsentReason.setValue(new CodeType("not-applicable"));
      aeob_summary.getProvider().addExtension(providerAbsentReason);

      aeob_summary.setOutcome(ExplanationOfBenefit.RemittanceOutcome.COMPLETE);

      Map<String, ExplanationOfBenefit.TotalComponent> totals_map = new HashMap<String, ExplanationOfBenefit.TotalComponent>();

      for (BundleEntryComponent e : aeobBundle.getEntry()) {
        IBaseResource bundleEntry = (IBaseResource) e.getResource();
        if (bundleEntry.fhirType().equals("ExplanationOfBenefit")) {
          // This should be a gfe
          ExplanationOfBenefit aeob = (ExplanationOfBenefit) bundleEntry;
          
          
          // initialize the summary aeob with data from the first found instance of each element 
          //  (it may be that any individual aeob may not have the data, but for these elements, if any contain the data, it needs to be expressed into the summary)
          if(!aeob_summary.hasPatient() && aeob.hasPatient())
          {
            aeob_summary.setPatient(aeob.getPatient());
          }

          // billablePeriod (need start to be earliest and end to be latest item service date)
          // billablePeriod (need start to be earliest and end to be latest item service date)
          for (BundleEntryComponent gfe_bundle_entry : gfeBundle.getEntry()) {
            IBaseResource gfe_entry = (IBaseResource) gfe_bundle_entry.getResource();
            if (gfe_entry.fhirType().equals("Claim")) {
              Claim gfe_claim = (Claim) gfe_entry;
              for (Claim.ItemComponent claim_item : gfe_claim.getItem()) {
                if(claim_item.hasServicedDateType())
                {
                  UpdateSummaryBillablePeriod(aeob_summary, claim_item.getServicedDateType().getValue(), claim_item.getServicedDateType().getValue());
                }
                else if(claim_item.hasServicedPeriod())
                {
                  UpdateSummaryBillablePeriod(aeob_summary, claim_item.getServicedPeriod().getStart(), claim_item.getServicedPeriod().getEnd());
                }

              }
            }
          }
          
          if(!aeob_summary.hasInsurer() && aeob.hasInsurer())
          {
            aeob_summary.setInsurer(aeob.getInsurer());
          }

          if(!aeob_summary.hasInsurance() && aeob.hasInsurance())
          {
            aeob_summary.setInsurance(aeob.getInsurance());
          }

          if(!aeob_summary.hasBenefitPeriod() && aeob.hasBenefitPeriod())
          {
            aeob_summary.setBenefitPeriod(aeob.getBenefitPeriod());
          }

          // Add all process notes. This is being done just for testing. Unlikely something that should generally be done, particularly in the case of duplicates. 
          // May want to remove duplicates for cleanliness
          if(aeob.hasProcessNote())
          {
            for (ExplanationOfBenefit.NoteComponent processNote : aeob.getProcessNote()) {
              aeob_summary.addProcessNote(processNote);
            }
          }


          
           // Add up totals
           for ( ExplanationOfBenefit.TotalComponent total : aeob.getTotal()){
              //totals_dict
              String total_category = new String();
              total_category = "";
              
              if (total.hasCategory() && total.getCategory().hasCoding())
              {
                for (Coding coding : total.getCategory().getCoding())
                {
                  if(coding.hasSystem() && coding.getSystem().equals("http://terminology.hl7.org/CodeSystem/adjudication"))
                  {
                    total_category = coding.getCode();
                  }
                }
                if(!total_category.equals("")){
                  // Found a category to add to dict or to sum up in dict
                  if(!totals_map.containsKey(total_category))
                  {
                    totals_map.put(total_category, total);
                  }
                  else{
                    totals_map.get(total_category).getAmount().setValue(totals_map.get(total_category).getAmount().getValue().doubleValue() + total.getAmount().getValue().doubleValue());
                  }
                }
              }
           }
           // TODO Benefit balance
           // Find Lowest balance and save or find the top benefit ballance and subtract the totals?

        }
        
      }
      for( String key : totals_map.keySet()){
        aeob_summary.addTotal(totals_map.get(key));
      }
      // Add AEOB Summary to Bundle
      Bundle.BundleEntryComponent summary_aeobBundleEntry = new Bundle.BundleEntryComponent();
      summary_aeobBundleEntry.setFullUrl("http://example.org/fhir/ExplanationOfBenefit/" + aeob_summary.getId());
      summary_aeobBundleEntry.setResource(aeob_summary);
      aeobBundle.getEntry().add(0, summary_aeobBundleEntry);
    } catch (Exception e) {
    		myLogger.info("Error: " + e.getMessage());
    }
    return aeobBundle; 
  }


  private void UpdateSummaryBillablePeriod(ExplanationOfBenefit eob, Date start_date, Date end_date) {
    Period period = new Period();
    if(start_date != null)
    {
      period.setStart(start_date);
    }
    if(end_date != null)
    {
      period.setEnd(end_date);
    }
    
    if(!eob.hasBillablePeriod())
    {
      eob.setBillablePeriod(period);
    }
    else{
      if(start_date != null)
      {
        if(!eob.getBillablePeriod().hasStart())
        {
          eob.getBillablePeriod().setStart(period.getStart());
        }
        else if(eob.getBillablePeriod().getStart().compareTo(period.getStart()) > 0)
        {
          eob.getBillablePeriod().setStart(period.getStart());
          
        }
      }

      if(end_date != null)
      {
        
        if(!eob.getBillablePeriod().hasEnd())
        {
          eob.getBillablePeriod().setEnd(period.getEnd());
        }
        else if(eob.getBillablePeriod().getEnd().compareTo(period.getEnd()) < 0)
        {
          eob.getBillablePeriod().setEnd(period.getEnd());
          
        }
      }
    }
    return;
  }



private List<ExplanationOfBenefit.TotalComponent> addTotals(Claim claim, ExplanationOfBenefit aeob,
		double eligibleAmountPercent, double cost, Money eligibleAmount) {
	myLogger.info("Processing totals");
	eligibleAmount.setValue(claim.getTotal().getValue().doubleValue() * eligibleAmountPercent);

	// Update the AEOB resource based on the claim. NOTE: additional work might need
	// to be done here
	// This just assumes that the numbers are the same to the total claim
	aeob.getTotal().get(0).setAmount(claim.getTotal());
	List<ExplanationOfBenefit.TotalComponent> eobTotals = new ArrayList<>();
	ExplanationOfBenefit.TotalComponent eob1Total = new ExplanationOfBenefit.TotalComponent();
	CodeableConcept total1Category = new CodeableConcept();

	total1Category.addCoding().setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAdjudicationCategoryCS")
	    .setCode("memberliability").setDisplay("Member Liability");
	eob1Total.setCategory(total1Category);
	Money ptp = new Money();
	ptp.setValue(Math.max(eligibleAmount.getValue().doubleValue() - cost, 0));
	eob1Total.setAmount(ptp);

	eobTotals.add(eob1Total);
	return eobTotals;
}

private double addItems(Claim claim, ExplanationOfBenefit aeob, double eligibleAmountPercent) {
	myLogger.info("Processing claim items");
	List<ExplanationOfBenefit.ItemComponent> eobItems = new ArrayList<>();
    double coType = rand.nextInt(3);
//	List<Claim.ItemComponent> gfeClaimItems = claim.getItem();
	double cost = 0;
	for (Claim.ItemComponent claimItem : claim.getItem()) {
	  cost = processItem(claim, eobItems, eligibleAmountPercent, coType, cost, claimItem);
	}
	aeob.setItem(eobItems);
	return cost;
}

private void addBenefitPeriod(ExplanationOfBenefit aeob) {
	Calendar cal = Calendar.getInstance();
	cal.set(Calendar.MONTH,1);
	cal.set(Calendar.DAY_OF_YEAR, 1);
	aeob.getBenefitPeriod().setStart(cal.getTime());
	cal.set(Calendar.MONTH,12);
	cal.set(Calendar.DAY_OF_MONTH,31);
	aeob.getBenefitPeriod().setEnd(cal.getTime());
}

private void addExtensions(Bundle gfeBundle, Claim claim, Bundle.BundleEntryComponent providerEntry, ExplanationOfBenefit aeob) {
	
	Extension gfeReference = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeReference");
	gfeReference.setValue(new Reference("Bundle/" + gfeBundle.getId()));
	if (providerEntry != null) {
	    IBaseResource providerResource = providerEntry.getResource();
	    if (getClaimProvider(claim, gfeBundle) != null) {
	    		myLogger.info("Saving provider");
	    		updateResource(providerResource);
	    } else {
	    		myLogger.info("Unable to resolve Claim.provider reference in GFE Bundle");
	    }
	}
	aeob.addExtension(gfeReference);

  Extension serviceExtension = new Extension(SERVICE_DESCRIPTION_EXTENSION);
  serviceExtension.setValue(new StringType("Example service"));
  aeob.addExtension(serviceExtension);
  
	return;
}

private void addProcessNote(ExplanationOfBenefit aeob) {
	CodeableConcept cc = new CodeableConcept();
	Coding c = new Coding();
	c.setCode("disclaimer");
	c.setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAEOBProcessNoteCS");
	cc.addCoding(c);
	Extension processNoteClass = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/processNoteClass",cc);
	NoteComponent note = aeob.addProcessNote();
	note.addExtension(processNoteClass);
	note.setText("Estimate only...");
}

private void addClaimIdentifierReference(Claim claim, ExplanationOfBenefit aeob) {
	Reference claimRef = new Reference();
    claimRef.setIdentifier(claim.getIdentifierFirstRep());
    aeob.setClaim(claimRef);
}

private void addEligible(Money eligibleAmount, List<ExplanationOfBenefit.TotalComponent> eobTotals) {
	ExplanationOfBenefit.TotalComponent eob3Total = new ExplanationOfBenefit.TotalComponent();
    CodeableConcept total3Category = new CodeableConcept();

    total3Category.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/adjudication").setCode("eligible")
        .setDisplay("Eligible Amount");
    eob3Total.setCategory(total3Category);
    eob3Total.setAmount(eligibleAmount);

    eobTotals.add(eob3Total);
}

private void addSubmitted(Claim claim, List<ExplanationOfBenefit.TotalComponent> eobTotals) {
	ExplanationOfBenefit.TotalComponent eob2Total = new ExplanationOfBenefit.TotalComponent();
    CodeableConcept total2Category = new CodeableConcept();
    // Use net for submitted and eligible amounts
    total2Category.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/adjudication").setCode("submitted")
        .setDisplay("Submitted Amount");
    eob2Total.setCategory(total2Category);
    eob2Total.setAmount(claim.getTotal());

    eobTotals.add(eob2Total);
}

private Bundle.BundleEntryComponent addProviderReference(Bundle gfeBundle, Claim claim, ExplanationOfBenefit aeob) {
	Bundle.BundleEntryComponent providerEntry  = getClaimProvider(claim, gfeBundle);
    if (providerEntry != null) {
    		myLogger.info("Adding provider");
    	    Reference provRef = claim.getProvider();
    	    aeob.setProvider(new Reference(provRef.getReference()));
    }
	return providerEntry;
}

private void addUniqueClaimIdentifier(ExplanationOfBenefit aeob) {
	List<Identifier> ids = aeob.getIdentifier();
    Identifier id = new Identifier();
    id.setSystem("urn:ietf:rfc:3986");
    CodeableConcept idType = new CodeableConcept();
    Coding c = new Coding();
    c.setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTIdentifierType");
    c.setCode("uc");
    c.setDisplay("Unique Claim ID");
    idType.getCoding().add(c);
    id.setType(idType);
    id.setValue("urn:uuid:" + UUID.randomUUID().toString());
    ids.add(id);
}

private double processItem(Claim claim, List<ExplanationOfBenefit.ItemComponent> eobItems, double eligibleAmountPercent,
		double coType, double cost, Claim.ItemComponent claimItem) {
	
	ExplanationOfBenefit.ItemComponent eobItem = new ExplanationOfBenefit.ItemComponent();
    
	// Calulate the net for the claim item if it is not available
      Money netValue = new Money();
      if (claimItem.getNet() == null) {
        if (claimItem.getUnitPrice() == null || claimItem.getQuantity() == null) {
          netValue.setValue(0); // If the information isn't available default to 0
          claimItem.setNet(netValue);
        } else {
          double value = claimItem.getUnitPrice().getValue().doubleValue()
              * claimItem.getQuantity().getValue().doubleValue();
          netValue.setValue(value);
          claimItem.setNet(netValue);
        }
      }
      // extensions - includes estimated service date
      
      
      for (Extension e : claimItem.getExtension()) {
    	  	eobItem.addExtension(e);
    	  	this.myLogger.info("Adding item extension: " + e.getUrl());
      }

      // sequence
      eobItem.setSequence(claimItem.getSequence());

      // revenue
      eobItem.setRevenue(claimItem.getRevenue());

      // productOrService
      eobItem.setProductOrService(claimItem.getProductOrService());

      // modifier
      eobItem.setModifier(claimItem.getModifier());

      // net
      eobItem.setNet(claimItem.getNet());

      // adjudication
      // Hastily put together for Connectathon. Could be architected better using an
      // array, dict, or map
      List<ExplanationOfBenefit.AdjudicationComponent> eobItemAdjudications = new ArrayList<>();
      ExplanationOfBenefit.AdjudicationComponent eobItem1Adjudication = new ExplanationOfBenefit.AdjudicationComponent();
      CodeableConcept adj1Category = new CodeableConcept();

      // currently adjudication is to pay whatever the provider charges. This could be
      // made more easy with a set or algorithms or data driven, also for adding
      // subjectToMedicalMgmt
      // Hard codes, which could be improved.
      
      adj1Category.addCoding().setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAdjudicationCategoryCS")
          .setCode("memberliability").setDisplay("Member Liability");
      eobItem1Adjudication.setCategory(adj1Category);
      eobItem1Adjudication.setAmount(claimItem.getNet());

      eobItemAdjudications.add(eobItem1Adjudication);

      // Submitted
      ExplanationOfBenefit.AdjudicationComponent eobItem2Adjudication = new ExplanationOfBenefit.AdjudicationComponent();
      CodeableConcept adj2Category = new CodeableConcept();
      // Use net for submitted and eligible amounts
      adj2Category.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/adjudication").setCode("submitted")
          .setDisplay("Submitted Amount");
      eobItem2Adjudication.setCategory(adj2Category);
      eobItem2Adjudication.setAmount(claimItem.getNet());

      // Mock adjudication for subject to medical mgmt
      subjectToMedicalManagementAdjudication(eobItemAdjudications, eobItem2Adjudication);

      // Eligible
      ExplanationOfBenefit.AdjudicationComponent eobItem3Adjudication = new ExplanationOfBenefit.AdjudicationComponent();
      CodeableConcept adj3Category = new CodeableConcept();

      adj3Category.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/adjudication").setCode("eligible")
          .setDisplay("Eligible Amount");
      eobItem3Adjudication.setCategory(adj3Category);
      Money amount = new Money();
      amount.setValue(claimItem.getNet().getValue().doubleValue() * eligibleAmountPercent);
      eobItem3Adjudication.setAmount(amount);
      eobItemAdjudications.add(eobItem3Adjudication);

      // Add Copay or Coinsurance if applicable
      if (coType < 2) {
        cost = addCoPayOrCoInsurance(coType, cost, claimItem, eobItemAdjudications);
      }
      if (eobItem.hasExtension(SERVICE_DESCRIPTION_EXTENSION) == false)
      	eobItem.addExtension(claimItem.getExtensionByUrl(SERVICE_DESCRIPTION_EXTENSION));
      eobItem.setAdjudication(eobItemAdjudications);
      if (eobItem.hasServiced() == false) {
    	  	if (claimItem.hasServiced()) eobItem.setServiced(claimItem.getServiced());
    	  	else if (claim.hasBillablePeriod()) eobItem.setServiced(claim.getBillablePeriod());
      }
      eobItems.add(eobItem);
	return cost;
}

private double addCoPayOrCoInsurance(double coType, double cost, Claim.ItemComponent claimItem,
		List<ExplanationOfBenefit.AdjudicationComponent> eobItemAdjudications) {
	Money amount2 = new Money();
	CodeableConcept adj4Category = new CodeableConcept();
	ExplanationOfBenefit.AdjudicationComponent eobItem4Adjudication = new ExplanationOfBenefit.AdjudicationComponent();
	// Use net for submitted and eligible amounts
	if (coType == 0) {
	  // Copay
	  adj4Category.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/adjudication").setCode("copay")
	      .setDisplay("CoPay");
	  cost += 20.0;
	  amount2.setValue(20);

	} 
	// RG: Should remove because only one memberliability adjudication slice is allowed
	/*
	else if (coType == 1) {
	  // coinsurance
	  adj4Category.addCoding()
	      .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAdjudicationCategoryCS")
	      .setCode("memberliability").setDisplay("Member Liability");
	  double costForItem = claimItem.getNet().getValue().doubleValue() * 0.2;
	  cost += costForItem;
	  amount2.setValue(costForItem);
	}
	*/
	
	eobItem4Adjudication.setCategory(adj4Category);
	eobItem4Adjudication.setAmount(amount2);
	eobItemAdjudications.add(eobItem4Adjudication);
	return cost;
}

private void subjectToMedicalManagementAdjudication(
		List<ExplanationOfBenefit.AdjudicationComponent> eobItemAdjudications,
		ExplanationOfBenefit.AdjudicationComponent eobItem2Adjudication) {
	if (rand.nextInt(6) == 0) {
        // Medical Management extension
        int codeType = rand.nextInt(4);
        Extension medMgmtExt;
        if (codeType == 0) {
          medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
        		  new CodeableConcept().addCoding(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS",
                          "concurrent-review", "Concurrent Review")));
        } else if (codeType == 1) {
          medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
              new CodeableConcept().addCoding(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS", 
                  "prior-auth", "Prior Authorization")));
        } else if (codeType == 2) {
          medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
              new CodeableConcept().addCoding(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS",
                  "step-therapy", "Step Therapy")));
        } else {
          medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
              new CodeableConcept().addCoding(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS", 
                  "fail-first", "Fail-First")));
        }

        eobItem2Adjudication.addExtension(medMgmtExt);
        eobItemAdjudications.add(eobItem2Adjudication);
      }
}

  /**
   * Add all resources into the new aeob bundle and update the aeob with the
   * institutional
   * 
   * @param claim      the claim for the aeob
   * @param gfeBundle  the bundle with all gfe resources
   * @param aeob       the aeob
   * @param aeobBundle the return bundle to be updated
   * @return the new bundle
   */
  public ExplanationOfBenefit convertInstitutional(Claim claim, Bundle gfeBundle, ExplanationOfBenefit aeob) {
    aeob.getType().getCoding().get(0).setCode("institutional");
    aeob.getType().getCoding().get(0).setDisplay("Institutional");
    myLogger.info("Processing Institutional Claim");

    for (BundleEntryComponent e : gfeBundle.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) e.getResource();
      String resource = jparser.encodeResourceToString(bundleEntry);
      if (bundleEntry.fhirType().equals("Patient")) {
        Patient patient = (Patient) bundleEntry;
        aeob.setPatient(new Reference(patient.getId()));
      } else if (bundleEntry.fhirType().equals("Organization")) {
        Organization org = (Organization) bundleEntry;
        if (org.getType().get(0).getCoding().get(0).getCode().equals("pay")
            && (claim.getInsurer().getReference().contains(org.getId())
                || org.getId().contains(claim.getInsurer().getReference()))) {
          aeob.setInsurer(new Reference(org.getId()));
        } else if (org.getType().get(0).getCoding().get(0).getCode().equals("prov")
            && (claim.getProvider().getReference().contains(org.getId())
                || org.getId().contains(claim.getProvider().getReference()))) {
          aeob.setProvider(new Reference(org.getId()));
        } else if (org.getType().get(0).getCoding().get(0).getCode().equals("institutional-submitter")) {
          for (Extension ex : claim
              .getExtensionsByUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeSubmitter")) {
            if (((Reference) ex.getValue()).getReference().contains(org.getId())) {
              aeob.setProvider(new Reference(org.getId()));
              break;
            }
          }
        }

      } else if (bundleEntry.fhirType().equals("Coverage")) {
        Coverage cov = (Coverage) bundleEntry;
        aeob.getInsurance().get(0).setCoverage(new Reference(cov.getId()));
      }
    }

    return aeob;
  }

  /**
   * Add all resources into the new aeob bundle and update the aeob with the
   * professional
   * 
   * @param claim      the claim for the aeob
   * @param gfeBundle  the bundle with all gfe resources
   * @param aeob       the aeob
   * @param aeobBundle the return bundle to be updated
   * @return the new bundle
   */
  public ExplanationOfBenefit convertProfessional(Claim claim, Bundle gfeBundle, ExplanationOfBenefit aeob) {
    aeob.getType().getCoding().get(0).setCode("professional");
    aeob.getType().getCoding().get(0).setDisplay("Professional");
    myLogger.info("Processing Professional Claim");

    for (BundleEntryComponent e : gfeBundle.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) e.getResource();
      String resource = jparser.encodeResourceToString(bundleEntry);
      if (bundleEntry.fhirType().equals("Patient")) {
        Patient patient = (Patient) bundleEntry;
        aeob.setPatient(new Reference(patient.getId()));
      } else if (bundleEntry.fhirType().equals("Organization")) {
        Organization org = (Organization) bundleEntry;
        myLogger.info("Found Organization");
        myLogger.info(org.getType().get(0).getCoding().get(0).getCode());
        myLogger.info(claim.getInsurer().getReference());
        myLogger.info(claim.getProvider().getReference());
        myLogger.info(org.getId());
        myLogger.info("----------");
        if (org.getType().get(0).getCoding().get(0).getCode().equals("pay")
            && (claim.getInsurer().getReference().contains(org.getId())
                || org.getId().contains(claim.getInsurer().getReference()))) {
          myLogger.info("Adding Insurer");
          aeob.setInsurer(new Reference(org.getId()));
        } else if (org.getType().get(0).getCoding().get(0).getCode().equals("prov")
            && claim.getProvider().getReference().contains("Organization")
            && (claim.getProvider().getReference().contains(org.getId())
                || org.getId().contains(claim.getProvider().getReference()))) {
          // Provider
          myLogger.info("Adding Provider with Organization");

          aeob.setProvider(new Reference(org.getId()));
        }
      } else if (bundleEntry.fhirType().equals("Coverage")) {
        Coverage cov = (Coverage) bundleEntry;
        aeob.getInsurance().get(0).setCoverage(new Reference(cov.getId()));
      } else if (bundleEntry.fhirType().equals("PractitionerRole")) {
        PractitionerRole pr = (PractitionerRole) bundleEntry;
        if (claim.getProvider().getReference().contains("PractitionerRole")
            && (claim.getProvider().getReference().contains(pr.getId())
                || pr.getId().contains(claim.getProvider().getReference()))) {

          myLogger.info("Adding Provider by PractitionerRole");

          aeob.setProvider(new Reference(pr.getId()));
        }
      }
    }

    return aeob;
  }

  public void convertGFEBundletoAEOBBundle(Bundle gfeBundle, Bundle aeobBundle) {
    
    for (BundleEntryComponent e : gfeBundle.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) e.getResource();
      if (bundleEntry.fhirType().equals("Claim")) {
        // This should be a gfe
        Claim claim = (Claim) bundleEntry;
        // load the base aeob
        String eob = FileLoader.loadResource("raw-aeob.json");
        ExplanationOfBenefit aeob = jparser.parseResource(ExplanationOfBenefit.class, eob);
        aeob.setCreated(new Date());
        // set the aeob values based on the gfe
        convertGFEtoAEOB(gfeBundle, claim, aeob, aeobBundle);

      }
      else{
        // The claims do not need to individual
        aeobBundle.addEntry(e);
      }
      
      // TODO Make sure the individual aeob references are working right
      // TODO Make sure the individual AEOB network stuff is working
      //aeobBundle.addEntry(e);
    }
    // TODO Add AEOB Summary
    AddAEOBSummarytoAEOBBundle(gfeBundle, aeobBundle);
    addGfeBundleToAeobBundle(gfeBundle, aeobBundle);
  }

  private void addBenefitBalance(ExplanationOfBenefit aeob) {
	
		BenefitBalanceComponent bbc = aeob.addBenefitBalance();
		bbc.setCategory(createCodeableConcept("1", "https://x12.org/codes/service-type-codes"));
		bbc.setUnit(createCodeableConcept("individual", "http://terminology.hl7.org/CodeSystem/benefit-unit"));
		bbc.setTerm(createCodeableConcept("annual", "http://terminology.hl7.org/CodeSystem/benefit-term"));
		BenefitComponent financial = bbc.addFinancial();
		financial.setType(createCodeableConcept("allowed", "http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTFinancialType"));
		Money allowed = new Money();
		allowed.setValue(5000);
		allowed.setCurrency("USD");
		financial.setAllowed(allowed);
		Money used = new Money();
		used.setValue(5000);
		used.setCurrency("USD");
		financial.setUsed(used);
  }
  
  private CodeableConcept createCodeableConcept(String code, String system) {
		Coding c = new Coding();
		c.setCode(code);
		c.setSystem(system);
		CodeableConcept cc = new CodeableConcept(c);
		return cc;
  }

/**
   * Parse the resource and create the new aeob bundle. Send the initial bundle in
   * the return
   * 
   * @param theRequest  the request with the resource
   * @param theResponse the response
   * @throws Exception any errors
   */
  public void handleSubmit(HttpServletRequest theRequest, HttpServletResponse theResponse) throws Exception {
    theResponse.setStatus(404);
    theResponse.setContentType("application/json");
    theResponse.setCharacterEncoding("UTF-8");
    theResponse.setHeader("Access-Control-Allow-Origin", "*");
    theResponse.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
    theResponse.setHeader("Access-Control-Allow-Headers",
        "X-Requested-With, Origin, Content-Type, Accept, Authorization");
    myLogger.info("Set the headers");

    String outputString = "";
   
    try {
      String contentType = theRequest.getHeader("Content-Type");
      String accept = theRequest.getHeader("Accept");
      myLogger.info("Content-Type: " + contentType);
      myLogger.info("Accept: " + accept);

      String resource = parseRequest(theRequest);
      
      Bundle gfeBundle;
      if (contentType.equals("application/fhir+xml") || contentType.equals("application/xml")) {
    	  	gfeBundle = xparser.parseResource(Bundle.class, resource);
      } else {
    	  	gfeBundle = jparser.parseResource(Bundle.class, resource);
      }

      myLogger.info("Converting GFE Bundle to AEOB Bundle");
      Bundle returnBundle = createBundle();
      try {
    	  	convertGFEBundletoAEOBBundle(gfeBundle, returnBundle);
      } catch (Exception e) {
    	  	myLogger.info("Error converting GFE Bundle to AEOB Bundle: " + e.getMessage());
      }

      myLogger.info("Storing AEOB Bundle");
      updateBundle(returnBundle);
      
      outputString = baseUrl + "/fhir/Claim/$gfe-submit-poll-status?_bundleId=" + returnBundle.getIdElement().getIdPart();

      myLogger.info("Returning 202 with Content-Location header");
      theResponse.setStatus(202);
      theResponse.setHeader("Content-Location", outputString);
      theResponse.setContentType("text/plain");
      
    } catch (Exception ex) {
      handleError(theRequest, theResponse, ex);
    }
    
    theResponse.getWriter().close();

  }
  
  BundleEntryComponent getClaimProvider(Claim claim, Bundle gfeBundle) {
	  BundleEntryComponent providerEntry = null;
	  String ref = claim.getProvider().getReference();
//	  myLogger.info("Provider reference: " + ref);
	  if (claim.getProvider().getReference() != null) {
		  for (BundleEntryComponent entry : gfeBundle.getEntry()) {
			  String fullUrl = entry.getFullUrl();
			//  myLogger.info("Entry: " + fullUrl);
			  if(fullUrl.endsWith(ref)) {
				  providerEntry = entry;
				  break;
			  }
		  }
	  }
	  return providerEntry;
  }


  public String getOutputString(HttpServletRequest theRequest, HttpServletResponse theResponse, IBaseResource resource) {

    String accept = theRequest.getHeader("Accept");
    String outputString;

    if (accept.equals("application/fhir+xml")) {
      theResponse.setContentType("application/fhir+xml");
      outputString = xparser.encodeResourceToString(resource);
    } else {
      theResponse.setContentType("application/json");
      outputString = jparser.encodeResourceToString(resource);
    }

    return outputString;

  }

  /**
   * Sends an OperationOutcome response with the provided exception details and a 500 status
   * 
   * @param theRequest  the request with the resource
   * @param theResponse the response
   * @param ex exception to turn into an OperationOutcome 500 response
   * @throws IOException response writer exception
   */
  public void handleError(HttpServletRequest theRequest, HttpServletResponse theResponse, Exception ex) throws IOException {
    OperationOutcome oo = new OperationOutcome();
    OperationOutcome.OperationOutcomeIssueComponent ooic = new OperationOutcome.OperationOutcomeIssueComponent();
    ooic.setSeverity(OperationOutcome.IssueSeverity.ERROR);
    ooic.setCode(OperationOutcome.IssueType.EXCEPTION);
    CodeableConcept cc = new CodeableConcept();
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    ex.printStackTrace(pw);
    pw.close();
    cc.setText(sw.toString());
    ooic.setDetails(cc);
    oo.addIssue(ooic);
    String outputString = getOutputString(theRequest, theResponse, oo);
    theResponse.setStatus(500);
    theResponse.getWriter().write(outputString);
  }

}
