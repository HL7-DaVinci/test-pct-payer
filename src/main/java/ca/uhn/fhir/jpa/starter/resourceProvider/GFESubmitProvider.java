package ca.uhn.fhir.jpa.starter.resourceProvider;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hl7.fhir.dstu2.model.Narrative.NarrativeStatus;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Money;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
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

  private Integer simulatedDelaySeconds = 60;

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


    // Randomized adjudication error response
    if (rand.nextInt(2) == 0) {
      adjudicationErrorResponse(theRequest, theResponse);
      return;
    }


    // Attempt to fetch bundle
    Bundle bundle = client.read().resource(Bundle.class).withId(bundleId).execute();

    if (bundle == null) {
      theResponse.setStatus(404);
      return;
    }
    
    // Simulate a bundle building delay for testing
    // If bundle was created less than two minutes ago we'll return the "in progress" 202 Accepted status
    if (Instant.now().isBefore(bundle.getTimestamp().toInstant().plusSeconds(simulatedDelaySeconds))) {
      theResponse.setStatus(202);
      theResponse.setHeader("Retry-After", simulatedDelaySeconds.toString());
      return;
    }

    
    String outputString = "";

    try {      
      String contentType = theRequest.getHeader("Content-Type");
      String accept = theRequest.getHeader("Accept");
      myLogger.info("Content-Type: " + contentType);
      myLogger.info("Accept: " + accept);

      if (accept.equals("application/fhir+xml")) {
        theResponse.setContentType("application/fhir+xml");
        outputString = xparser.encodeResourceToString((IBaseResource) bundle);
      } else {
        theResponse.setContentType("application/json");
        outputString = jparser.encodeResourceToString((IBaseResource) bundle);
      }

      theResponse.setStatus(200);
      theResponse.getWriter().write(outputString);
      theResponse.getWriter().close();

    } catch (Exception e) {
      OperationOutcome.OperationOutcomeIssueComponent ooic = new OperationOutcome.OperationOutcomeIssueComponent();
      ooic.setSeverity(OperationOutcome.IssueSeverity.ERROR);
      ooic.setCode(OperationOutcome.IssueType.EXCEPTION);
      myLogger.info(e.getMessage());
      e.printStackTrace();
      theResponse.setStatus(500);
    }


  }

  public void adjudicationErrorResponse(HttpServletRequest theRequest, HttpServletResponse theResponse) {


    String adjudicationError = FileLoader.loadResource("raw-adjudication-error.json");
    OperationOutcome oo = jparser.parseResource(OperationOutcome.class, adjudicationError);

    String accept = theRequest.getHeader("Accept");
    String outputString;

    if (accept.equals("application/fhir+xml")) {
      theResponse.setContentType("application/fhir+xml");
      outputString = xparser.encodeResourceToString((OperationOutcome) oo);
    } else {
      theResponse.setContentType("application/json");
      outputString = jparser.encodeResourceToString((OperationOutcome) oo);
    }


    try {
      theResponse.setStatus(418);
      theResponse.getWriter().write(outputString);
      theResponse.getWriter().close();
    } catch (Exception e) {
      OperationOutcome.OperationOutcomeIssueComponent ooic = new OperationOutcome.OperationOutcomeIssueComponent();
      ooic.setSeverity(OperationOutcome.IssueSeverity.ERROR);
      ooic.setCode(OperationOutcome.IssueType.EXCEPTION);
      myLogger.info(e.getMessage());
      e.printStackTrace();
      theResponse.setStatus(500);
    }

  }


  /**
   * Create a new bundle with type Collection and an identifier to persist and be
   * used for queries
   * 
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
   * 
   * @param aeob the aeob to create in the server
   * @return the result which has the new ID
   */
  public ExplanationOfBenefit createAEOB(ExplanationOfBenefit aeob) {
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
    List<Extension> gfeExts = new ArrayList<>();
    Extension gfeReference = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeReference");
    gfeExts.add(gfeReference);
    Extension disclaimer = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/disclaimer",
        new StringType("Estimate Only ..."));
    gfeExts.add(disclaimer);

    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.MONTH, 6);
    Extension expirationDate = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/expirationDate",
        new DateType(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)));
    gfeExts.add(expirationDate);

    aeob.setExtension(gfeExts);

    Bundle.BundleEntryComponent providerEntry  = getClaimProvider(claim, gfeBundle);
    if (providerEntry != null) {
    		myLogger.info("Adding provider");
    	    Reference provRef = claim.getProvider();
    	    aeob.setProvider(new Reference(provRef.getReference()));
    }
    
    List<ExplanationOfBenefit.ItemComponent> eobItems = new ArrayList<>();

    double eligibleAmountPercent = (100.0 - rand.nextInt(21)) / 100.0;
    double coType = rand.nextInt(3);

    myLogger.info("Processing claim items");
    List<Claim.ItemComponent> gfeClaimItems = claim.getItem();
    double cost = 0;
    for (Claim.ItemComponent claimItem : gfeClaimItems) {
      cost = processItem(eobItems, eligibleAmountPercent, coType, cost, claimItem);
    }
    aeob.setItem(eobItems);

    // Totals

    myLogger.info("Processing totals");
    Money eligibleAmount = new Money();
    eligibleAmount.setValue(claim.getTotal().getValue().doubleValue() * eligibleAmountPercent);

    // Update the AEOB resource based on the claim. NOTE: additional work might need
    // to be done here
    // This just assumes that the numbers are the same to the total claim
    aeob.getTotal().get(0).setAmount(claim.getTotal());
    List<ExplanationOfBenefit.TotalComponent> eobTotals = new ArrayList<>();
    ExplanationOfBenefit.TotalComponent eob1Total = new ExplanationOfBenefit.TotalComponent();
    CodeableConcept total1Category = new CodeableConcept();

    total1Category.addCoding().setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAdjudicationCategoryTypeCS")
        .setCode("paidtoprovider").setDisplay("Paid to Provider");
    eob1Total.setCategory(total1Category);
    Money ptp = new Money();
    ptp.setValue(Math.max(eligibleAmount.getValue().doubleValue() - cost, 0));
    eob1Total.setAmount(ptp);

    eobTotals.add(eob1Total);

    // Submitted
    ExplanationOfBenefit.TotalComponent eob2Total = new ExplanationOfBenefit.TotalComponent();
    CodeableConcept total2Category = new CodeableConcept();
    // Use net for submitted and eligible amounts
    total2Category.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/adjudication").setCode("submitted")
        .setDisplay("Submitted Amount");
    eob2Total.setCategory(total2Category);
    eob2Total.setAmount(claim.getTotal());

    eobTotals.add(eob2Total);

    // Eligible
    ExplanationOfBenefit.TotalComponent eob3Total = new ExplanationOfBenefit.TotalComponent();
    CodeableConcept total3Category = new CodeableConcept();

    total3Category.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/adjudication").setCode("eligible")
        .setDisplay("Eligible Amount");
    eob3Total.setCategory(total3Category);
    eob3Total.setAmount(eligibleAmount);

    eobTotals.add(eob3Total);

    aeob.setTotal(eobTotals);

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
    
    myLogger.info("Saving AEOB");
    	aeob = createAEOB(aeob);

    
    myLogger.info("Adding AEOB to AEOB Bundle");
    Bundle.BundleEntryComponent aeobEntry = new Bundle.BundleEntryComponent();

    aeobEntry.setFullUrl("http://example.org/fhir/ExplanationOfBenefit/" + aeob.getId().split("/_history")[0]);
    aeobEntry.setResource(aeob);
    aeobBundle.addEntry(aeobEntry);
    
//    aeobBundle.addEntry(providerEntry);
    
    myLogger.info("Adding GFE Bundle to AEOB Bundle");
    Bundle.BundleEntryComponent gfeBundleEntry = new Bundle.BundleEntryComponent();
    gfeBundleEntry.setFullUrl("http://example.org/fhir/Bundle/" + gfeBundle.getId());
    gfeBundleEntry.setResource(gfeBundle);
    aeobBundle.addEntry(gfeBundleEntry);
    
    for (Extension ex : claim
        .getExtensionsByUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeProviderAssignedIdentifier")) {
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
    return aeobBundle;
  }

private double processItem(List<ExplanationOfBenefit.ItemComponent> eobItems, double eligibleAmountPercent,
		double coType, double cost, Claim.ItemComponent claimItem) {
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
      ExplanationOfBenefit.ItemComponent eobItem = new ExplanationOfBenefit.ItemComponent();
      // extensions - includes estimated service date
      eobItem.setExtension(claimItem.getExtension());

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
      adj1Category.addCoding().setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAdjudicationCategoryTypeCS")
          .setCode("paidtoprovider").setDisplay("Paid to Provider");
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

      eobItem.setAdjudication(eobItemAdjudications);
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

	} else if (coType == 1) {
	  // coinsurance
	  adj4Category.addCoding()
	      .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAdjudicationCategoryTypeCS")
	      .setCode("coinsurance").setDisplay("Co-insurance");
	  double costForItem = claimItem.getNet().getValue().doubleValue() * 0.2;
	  cost += costForItem;
	  amount2.setValue(costForItem);
	}
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
              new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS",
                  "concurrent-review", "Concurrent Review"));
        } else if (codeType == 1) {
          medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
              new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS", "prior-auth",
                  "Prior Authorization"));
        } else if (codeType == 2) {
          medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
              new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS",
                  "step-therapy", "Step Therapy"));
        } else {
          medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
              new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS", "fail-first",
                  "Fail First"));
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

    for (Bundle.BundleEntryComponent e : gfeBundle.getEntry()) {
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

    for (Bundle.BundleEntryComponent e : gfeBundle.getEntry()) {
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
    for (Bundle.BundleEntryComponent e : gfeBundle.getEntry()) {
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
      aeobBundle.addEntry(e);
    }
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
        "X-Requested-With,Origin,Content-Type, Accept, Authorization");
    myLogger.info("Set the headers");

    String outputString = "";
   
    try {
      String contentType = theRequest.getHeader("Content-Type");
      String accept = theRequest.getHeader("Accept");
      myLogger.info("Content-Type: " + contentType);
      myLogger.info("Accept: " + accept);
      Bundle returnBundle = createBundle();

      String resource = parseRequest(theRequest);

//      myLogger.info("Parsed resource: " + resource);
      Bundle gfeBundle;
      if (contentType.equals("application/fhir+xml") || contentType.equals("application/xml")) {
    	  	gfeBundle = xparser.parseResource(Bundle.class, resource);
      } else {
    	  	gfeBundle = jparser.parseResource(Bundle.class, resource);
      }
      myLogger.info("Converting GFE Bundle to AEOB Bundle");
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
      OperationOutcome oo = new OperationOutcome();
      OperationOutcome.OperationOutcomeIssueComponent ooic = new OperationOutcome.OperationOutcomeIssueComponent();
      ooic.setSeverity(OperationOutcome.IssueSeverity.ERROR);
      ooic.setCode(OperationOutcome.IssueType.EXCEPTION);
      CodeableConcept cc = new CodeableConcept();
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      ex.printStackTrace(pw);
      pw.close();
   //   cc.setText(ex.getMessage());
      cc.setText(sw.toString());
      ooic.setDetails(cc);
      oo.addIssue(ooic);
      outputString = jparser.encodeResourceToString((IBaseResource) oo);
     
    }
    
    theResponse.getWriter().close();

  }
  
  Bundle.BundleEntryComponent getClaimProvider(Claim claim, Bundle gfeBundle) {
	  Bundle.BundleEntryComponent providerEntry = null;
	  String ref = claim.getProvider().getReference();
//	  myLogger.info("Provider reference: " + ref);
	  if (claim.getProvider().getReference() != null) {
		  for (Bundle.BundleEntryComponent entry : gfeBundle.getEntry()) {
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
}
