package com.lantanagroup.providers;

import com.lantanagroup.common.util;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.Date;
import java.util.Calendar;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
//import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.hl7.fhir.instance.model.api.IBaseResource;
//import org.hl7.fhir.instance.model.api.IIdType;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Bundle.*;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.NoteComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.BenefitBalanceComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.BenefitComponent;

//import com.apicatalog.jsonld.loader.FileLoader;
//import com.lantanagroup.common.ProcessCustomizer;

//import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
//import ca.uhn.fhir.rest.api.server.ResponseDetails;
//import ca.uhn.fhir.rest.api.server.ResponseDetails;


// TODO Function documentation 
// TODO Refactor response handling (currently using servlett, but is that the current preferred method?)

public class GfeSubmitProvider {
  private static final String SERVICE_DESCRIPTION_EXTENSION = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/serviceDescription";
  private static final String DATA_ABSENT_REASON_EXTENSION = "http://hl7.org/fhir/StructureDefinition/data-absent-reason";
  private static final String PCT_GFE_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-bundle";
  private static final String PCT_GFE_MISSING_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-missing-bundle";
  private static final String PCT_GFE_COLLECTION_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-collection-bundle";

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GfeSubmitProvider.class);
  private FhirContext theFhirContext;
  private IFhirResourceDao<Bundle> theBundleDao;
  private IFhirResourceDao<Practitioner> thePractitionerDao;
  private IFhirResourceDao<PractitionerRole> thePractitionerRoleDao;
  private IFhirResourceDao<Organization> theOrganizationDao;
  private IFhirResourceDao<ExplanationOfBenefit> theExplanationOfBenefitDao;

  private Integer simulatedDelaySeconds = 15;
  private IParser jparser;
  private IParser xparser;
  private Random rand;

  public GfeSubmitProvider(FhirContext ctx, DaoRegistry daoRegistry) {
    this.theFhirContext = ctx;
    theBundleDao = daoRegistry.getResourceDao(Bundle.class);
    thePractitionerDao = daoRegistry.getResourceDao(Practitioner.class);
    thePractitionerRoleDao = daoRegistry.getResourceDao(PractitionerRole.class);
    theOrganizationDao = daoRegistry.getResourceDao(Organization.class);
    theExplanationOfBenefitDao = daoRegistry.getResourceDao(ExplanationOfBenefit.class);

    jparser = this.theFhirContext.newJsonParser();
    jparser.setPrettyPrint(true);
    rand = new Random();

  }

  // #region Operations

  @Operation(name = "$gfe-submit", type = Claim.class, manualResponse = true)
  public void gfeSubmit(
      @OperationParam(name = "resource", min = 1, max = 1, type = Bundle.class) Bundle theBundleResource,
      RequestDetails theRequestDetails,
      HttpServletRequest theRequest,
      HttpServletResponse theResponse) {

    logger.info("Received GFE Submit");

    try {
      handleSubmit(theBundleResource, theRequestDetails, theRequest, theResponse);
    } catch (Exception e) {
      logger.info("Error in submission");
      logger.info(e.getMessage());
      e.printStackTrace();
    }

  }

  
  @Operation(name = "$gfe-submit-poll-status", type = Claim.class, manualResponse = true, manualRequest = true, idempotent = true)
  public void getPollStatus(
      @OperationParam(name = "_bundleId", min = 1, max = 1) String bundleId,
      RequestDetails theRequestDetails,
      HttpServletRequest theRequest,
      HttpServletResponse theResponse) throws IOException {

    // Attempt to fetch bundle
    Bundle bundle = null;

    try {

      // Get existing task to see if the status is rejected or completed
      bundle = theBundleDao.read(new IdType(bundleId), theRequestDetails);
    } catch (Exception e) {
      logger.info("Unable to retrieve Bundle with id: {}", bundleId);
      theResponse.setStatus(404);
      return;
    }


    // Simulate a bundle building delay for testing
    // If bundle was created less than the set number of simluated delay seconds
    // we'll return the "in progress" 202 Accepted status
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
          logger.info("Found patient family name: {}", familyName);

          // Patient last name of "Coffee" or "Error" will return adjudication error response
          if (familyName.toLowerCase().equals("coffee") || familyName.toLowerCase().equals("error")) {
            adjudicationErrorResponse(theRequest, theResponse, bundle);
            return;
          }

          // Patient last name of "Private" will return "PCT AEOB Complete" response
          // instead of bundle
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
      logger.info("Content-Type: " + contentType);
      logger.info("Accept: " + accept);

      String outputString = getOutputString(theRequest, theResponse, responseBundle);

      theResponse.setStatus(200);
      theResponse.getWriter().write(outputString);
      theResponse.getWriter().close();

    } catch (Exception e) {
      handleError(theRequest, theResponse, e);
    }

  }

  // #endregion Operations

  // #region GFE-Submit Operation Handlers
  /**
   * Parse the resource and create the new aeob bundle. Send the initial bundle in
   * the return
   * 
   * @param theRequest  the request with the resource
   * @param theResponse the response
   * @throws Exception any errors
   */
  public void handleSubmit(Bundle theBundleResource, RequestDetails theRequestDetails, HttpServletRequest theRequest,
      HttpServletResponse theResponse) throws Exception {
    theResponse.setStatus(404);
    theResponse.setContentType("application/json");
    theResponse.setCharacterEncoding("UTF-8");
    theResponse.setHeader("Access-Control-Allow-Origin", "*");
    theResponse.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
    theResponse.setHeader("Access-Control-Allow-Headers",
        "X-Requested-With, Origin, Content-Type, Accept, Authorization");
    logger.info("Set the headers");

    String outputString = "";

    try {
      // Old operation body parsing
      // String contentType = theRequest.getHeader("Content-Type");
      // String accept = theRequest.getHeader("Accept");
      // logger.info("Content-Type: " + contentType);
      // logger.info("Accept: " + accept);

      // String resource = parseRequest(theRequest);

      // Bundle gfeBundle;
      // if (contentType.equals("application/fhir+xml") ||
      // contentType.equals("application/xml")) {
      // gfeBundle = xparser.parseResource(Bundle.class, resource);
      // } else {
      // gfeBundle = jparser.parseResource(Bundle.class, resource);
      // }

      logger.info("Converting GFE Bundle to AEOB Bundle");
      Bundle returnBundle = new Bundle();
      returnBundle.setType(BundleType.COLLECTION);
      //Identifier identifier = new Identifier().setSystem(theRequestDetails.getFhirServerBase() + "/documentIDs").setValue(UUID.randomUUID().toString());
      
      //String uuid = UUID.randomUUID().toString();
      //identifier.setValue(uuid);
      returnBundle.setIdentifier(new Identifier().setSystem(theRequestDetails.getFhirServerBase() + "/documentIDs").setValue(UUID.randomUUID().toString()));
      returnBundle.setTimestamp(new Date());

      try {
        convertGFEBundletoAEOBBundle(theBundleResource, returnBundle, theRequestDetails);
      } catch (Exception e) {
        logger.info("Error converting GFE Bundle to AEOB Bundle: " + e.getMessage());
      }

      logger.info("Storing AEOB Bundle");
      // updateBundle(returnBundle);
      theBundleDao.create(returnBundle, theRequestDetails);

      outputString = theRequestDetails.getFhirServerBase() + "/Claim/$gfe-submit-poll-status?_bundleId=" + returnBundle.getIdElement().getIdPart();

      logger.info("Returning 202 with Content-Location header");
      theResponse.setStatus(202);
      theResponse.setHeader("Content-Location", outputString);
      theResponse.setContentType("text/plain");

    } catch (Exception ex) {
      handleError(theRequest, theResponse, ex);
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
    logger.info("Processing Institutional Claim");

    for (BundleEntryComponent e : gfeBundle.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) e.getResource();
      //String resource = jparser.encodeResourceToString(bundleEntry);
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
    logger.info("Processing Professional Claim");

    for (BundleEntryComponent e : gfeBundle.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) e.getResource();
      //String resource = jparser.encodeResourceToString(bundleEntry);
      if (bundleEntry.fhirType().equals("Patient")) {
        Patient patient = (Patient) bundleEntry;
        aeob.setPatient(new Reference(patient.getId()));
      } else if (bundleEntry.fhirType().equals("Organization")) {
        Organization org = (Organization) bundleEntry;
        logger.info("Found Organization");
        logger.info(org.getType().get(0).getCoding().get(0).getCode());
        logger.info(claim.getInsurer().getReference());
        logger.info(claim.getProvider().getReference());
        logger.info(org.getId());
        logger.info("----------");
        if (org.getType().get(0).getCoding().get(0).getCode().equals("pay")
            && (claim.getInsurer().getReference().contains(org.getId())
                || org.getId().contains(claim.getInsurer().getReference()))) {
          logger.info("Adding Insurer");
          aeob.setInsurer(new Reference(org.getId()));
        } else if (org.getType().get(0).getCoding().get(0).getCode().equals("prov")
            && claim.getProvider().getReference().contains("Organization")
            && (claim.getProvider().getReference().contains(org.getId())
                || org.getId().contains(claim.getProvider().getReference()))) {
          // Provider
          logger.info("Adding Provider with Organization");

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

          logger.info("Adding Provider by PractitionerRole");

          aeob.setProvider(new Reference(pr.getId()));
        }
      }
    }

    return aeob;
  }

  public void convertGFEBundletoAEOBBundle(Bundle gfeBundle, Bundle aeobBundle, RequestDetails theRequestDetails) {

    boolean isCollectionBundle = false;
    for (BundleEntryComponent e : gfeBundle.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) e.getResource();

      // Claim type should be converted to AEOB
      if (bundleEntry.fhirType().equals("Claim")) {
        claimToAEOB((Claim) bundleEntry, gfeBundle, aeobBundle, theRequestDetails);
      }

      // If the entry is a bundle, process the claims in the bundle
      else if (bundleEntry.fhirType().equals("Bundle")) {
        Bundle innerBundle = (Bundle) bundleEntry;
        for (BundleEntryComponent innerEntry : innerBundle.getEntry()) {
          IBaseResource innerBundleEntry = (IBaseResource) innerEntry.getResource();
          if (innerBundleEntry.fhirType().equals("Claim")) {
            isCollectionBundle = true;
            claimToAEOB((Claim) innerBundleEntry, gfeBundle, aeobBundle, theRequestDetails);
          }
        }
      }

      // Add all other resources to the return bundle
      else {
        aeobBundle.addEntry(e);
      }

      // TODO Make sure the individual aeob references are working right
      // TODO Make sure the individual AEOB network stuff is working
      // aeobBundle.addEntry(e);
    }
    // TODO Add AEOB Summary
    addAEOBSummarytoAEOBBundle(gfeBundle, aeobBundle, theRequestDetails);

    // Copy GFE bundle(s) to AEOB Bundle
    // If this is a collection bundle, add all the GFE bundles
    // Can't be sure a profile will be provided. Context needs to be sent in from caller.
    //if (gfeBundle.getMeta().getProfile().get(0).equals(PCT_GFE_COLLECTION_BUNDLE_PROFILE)) {
    if(isCollectionBundle)
    {
      for (BundleEntryComponent e : gfeBundle.getEntry()) {
        IBaseResource bundleEntry = (IBaseResource) e.getResource();
        if (bundleEntry.fhirType().equals("Bundle")) {
          addGfeBundleToAeobBundle((Bundle) bundleEntry, aeobBundle, theRequestDetails);
        }
      }
    } 
    else {
      addGfeBundleToAeobBundle(gfeBundle, aeobBundle, theRequestDetails);
    }
  }


  public void claimToAEOB(Claim claim, Bundle gfeBundle, Bundle aeobBundle, RequestDetails theRequestDetails) {
    // load the base aeob
    String eob = util.loadResource("templates/raw-aeob.json");
    ExplanationOfBenefit aeob = jparser.parseResource(ExplanationOfBenefit.class, eob);
    aeob.setCreated(new Date());
    // set the aeob values based on the gfe
    convertGFEtoAEOB(gfeBundle, claim, aeob, aeobBundle, theRequestDetails);
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
  public Bundle convertGFEtoAEOB(Bundle gfeBundle, Claim claim, ExplanationOfBenefit aeob, Bundle aeobBundle,
      RequestDetails theRequestDetails) {
    logger.info("Converting GFE to AEOB");
    try {

      Bundle.BundleEntryComponent providerEntry = addProviderReference(gfeBundle, claim, aeob);

      addExtensions(gfeBundle, claim, providerEntry, aeob, theRequestDetails);

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

      logger.info("Saving AEOB");
      // aeob = saveAeob(aeob);

      theExplanationOfBenefitDao.create(aeob, theRequestDetails);
      addAeobToBundle(aeob, aeobBundle, theRequestDetails);

      // This is unnecessary because this function gets called for each claim. This
      // call would add a GFE bundle for each claim in the GFEBundle.
      // addGfeBundleToAeobBundle(gfeBundle, aeobBundle);

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

      logger.info("Updating AEOB");
      // TODO Check why saving earlier and updating now
      // updateAEOB(aeob);
      theExplanationOfBenefitDao.update(aeob, theRequestDetails);
    } catch (Exception e) {
      logger.info("Error: " + e.getMessage());
    }
    return aeobBundle;
  }

  BundleEntryComponent getClaimProvider(Claim claim, Bundle gfeBundle) {
    BundleEntryComponent providerEntry = null;
    String ref = claim.getProvider().getReference();
    // logger.info("Provider reference: " + ref);
    if (claim.getProvider().getReference() != null) {
      for (BundleEntryComponent entry : gfeBundle.getEntry()) {
        String fullUrl = entry.getFullUrl();
        // logger.info("Entry: " + fullUrl);
        if (fullUrl.endsWith(ref)) {
          providerEntry = entry;
          break;
        }
      }
    }
    return providerEntry;
  }

  private double processItem(Claim claim, List<ExplanationOfBenefit.ItemComponent> eobItems,
      double eligibleAmountPercent,
      double coType, double cost, Claim.ItemComponent claimItem) {

    ExplanationOfBenefit.ItemComponent eobItem = new ExplanationOfBenefit.ItemComponent();

    // Calculate the net for the claim item if it is not available
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
      logger.info("Adding item extension: " + e.getUrl());
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
      if (claimItem.hasServiced())
        eobItem.setServiced(claimItem.getServiced());
      else if (claim.hasBillablePeriod())
        eobItem.setServiced(claim.getBillablePeriod());
    }
    eobItems.add(eobItem);
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
            new CodeableConcept()
                .addCoding(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS",
                    "concurrent-review", "Concurrent Review")));
      } else if (codeType == 1) {
        medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
            new CodeableConcept()
                .addCoding(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS",
                    "prior-auth", "Prior Authorization")));
      } else if (codeType == 2) {
        medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
            new CodeableConcept()
                .addCoding(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS",
                    "step-therapy", "Step Therapy")));
      } else {
        medMgmtExt = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/subjectToMedicalMgmt",
            new CodeableConcept()
                .addCoding(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTSubjectToMedicalMgmtReasonCS",
                    "fail-first", "Fail-First")));
      }

      eobItem2Adjudication.addExtension(medMgmtExt);
      eobItemAdjudications.add(eobItem2Adjudication);
    }
  }

  /**
   * Add AEOB Summary EOB to the AOEB Bundle
   * 
   * @param aeobBundle the bundle to add to summary to
   * @return the complete aeob bundle
   */
  public Bundle addAEOBSummarytoAEOBBundle(Bundle gfeBundle, Bundle aeobBundle, RequestDetails theRequestDetails) {
    logger.info("Summarizing AEOB Bundle");
    try {
      ExplanationOfBenefit aeob_summary = new ExplanationOfBenefit();
      Meta aeob_summary_meta = new Meta();
      aeob_summary_meta.setVersionId("1");
      aeob_summary_meta.setLastUpdated(new Date());
      aeob_summary_meta.addProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-summary");
      aeob_summary.setMeta(aeob_summary_meta);
      aeob_summary.setId("PCT-AEOB-Summary");

      Extension outOfNetworkProviderInfo = new Extension(
          "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/inNetworkProviderOptionsLink");
      outOfNetworkProviderInfo.setValue(new UrlType("http://example.com/out-of-networks.html"));
      aeob_summary.addExtension(outOfNetworkProviderInfo);

      Extension serviceExtension = new Extension(SERVICE_DESCRIPTION_EXTENSION);
      serviceExtension.setValue(new StringType(
          "Example service - Should this really be required for a summary? How would the payer summarize a service description across all EOBs?"));

      aeob_summary.setStatus(ExplanationOfBenefitStatus.ACTIVE);

      aeob_summary.setType(new CodeableConcept(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAEOBTypeSummaryCS", "eob-summary", "Explanation of Benefit Summary")));

      aeob_summary.setUse(ExplanationOfBenefit.Use.PREDETERMINATION);

      aeob_summary.setCreated(aeob_summary_meta.getLastUpdated());

      Extension providerAbsentReason = new Extension(DATA_ABSENT_REASON_EXTENSION);
      //Coding darCoding = new Coding();
      //darCoding.setCode("not-applicable");
      providerAbsentReason.setValue(new CodeType("not-applicable"));
      aeob_summary.getProvider().addExtension(providerAbsentReason);

      aeob_summary.setOutcome(ExplanationOfBenefit.RemittanceOutcome.COMPLETE);

      Map<String, ExplanationOfBenefit.TotalComponent> totals_map = new HashMap<String, ExplanationOfBenefit.TotalComponent>();

      for (BundleEntryComponent e : aeobBundle.getEntry()) {
        IBaseResource bundleEntry = (IBaseResource) e.getResource();
        if (bundleEntry.fhirType().equals("ExplanationOfBenefit")) {
          // This should be a gfe
          ExplanationOfBenefit aeob = (ExplanationOfBenefit) bundleEntry;

          // initialize the summary aeob with data from the first found instance of each
          // element
          // (it may be that any individual aeob may not have the data, but for these
          // elements, if any contain the data, it needs to be expressed into the summary)
          if (!aeob_summary.hasPatient() && aeob.hasPatient()) {
            aeob_summary.setPatient(aeob.getPatient());
          }

          // billablePeriod (need start to be earliest and end to be latest item service
          // date)
          // billablePeriod (need start to be earliest and end to be latest item service
          // date)
          for (BundleEntryComponent gfe_bundle_entry : gfeBundle.getEntry()) {
            IBaseResource gfe_entry = (IBaseResource) gfe_bundle_entry.getResource();
            if (gfe_entry.fhirType().equals("Claim")) {
              Claim gfe_claim = (Claim) gfe_entry;
              for (Claim.ItemComponent claim_item : gfe_claim.getItem()) {
                if (claim_item.hasServicedDateType()) {
                  UpdateSummaryBillablePeriod(aeob_summary, claim_item.getServicedDateType().getValue(),
                      claim_item.getServicedDateType().getValue());
                } else if (claim_item.hasServicedPeriod()) {
                  UpdateSummaryBillablePeriod(aeob_summary, claim_item.getServicedPeriod().getStart(),
                      claim_item.getServicedPeriod().getEnd());
                }

              }
            }
          }

          if (!aeob_summary.hasInsurer() && aeob.hasInsurer()) {
            aeob_summary.setInsurer(aeob.getInsurer());
          }

          if (!aeob_summary.hasInsurance() && aeob.hasInsurance()) {
            aeob_summary.setInsurance(aeob.getInsurance());
          }

          if (!aeob_summary.hasBenefitPeriod() && aeob.hasBenefitPeriod()) {
            aeob_summary.setBenefitPeriod(aeob.getBenefitPeriod());
          }

          // Add all process notes. This is being done just for testing. Unlikely
          // something that should generally be done, particularly in the case of
          // duplicates.
          // May want to remove duplicates for cleanliness
          if (aeob.hasProcessNote()) {
            for (ExplanationOfBenefit.NoteComponent processNote : aeob.getProcessNote()) {
              aeob_summary.addProcessNote(processNote);
            }
          }

          // Add up totals
          for (ExplanationOfBenefit.TotalComponent total : aeob.getTotal()) {
            // totals_dict
            String total_category = new String();
            total_category = "";

            if (total.hasCategory() && total.getCategory().hasCoding()) {
              for (Coding coding : total.getCategory().getCoding()) {
                if (coding.hasSystem()
                    && coding.getSystem().equals("http://terminology.hl7.org/CodeSystem/adjudication")) {
                  total_category = coding.getCode();
                }
              }
              if (!total_category.equals("")) {
                // Found a category to add to dict or to sum up in dict
                if (!totals_map.containsKey(total_category)) {
                  totals_map.put(total_category, total);
                } else {
                  totals_map.get(total_category).getAmount()
                      .setValue(totals_map.get(total_category).getAmount().getValue().doubleValue()
                          + total.getAmount().getValue().doubleValue());
                }
              }
            }
          }
          // TODO Benefit balance
          // Find Lowest balance and save or find the top benefit ballance and subtract
          // the totals?

        }

      }
      for (String key : totals_map.keySet()) {
        aeob_summary.addTotal(totals_map.get(key));
      }
      // Add AEOB Summary to Bundle
      Bundle.BundleEntryComponent summary_aeobBundleEntry = new Bundle.BundleEntryComponent();
      summary_aeobBundleEntry.setFullUrl(theRequestDetails.getFhirServerBase() + "/ExplanationOfBenefit/" + aeob_summary.getId());
      summary_aeobBundleEntry.setResource(aeob_summary);
      aeobBundle.getEntry().add(0, summary_aeobBundleEntry);
    } catch (Exception e) {
      logger.info("Error: " + e.getMessage());
    }
    return aeobBundle;
  }

  private void UpdateSummaryBillablePeriod(ExplanationOfBenefit eob, Date start_date, Date end_date) {
    Period period = new Period();
    if (start_date != null) {
      period.setStart(start_date);
    }
    if (end_date != null) {
      period.setEnd(end_date);
    }

    if (!eob.hasBillablePeriod()) {
      eob.setBillablePeriod(period);
    } else {
      if (start_date != null) {
        if (!eob.getBillablePeriod().hasStart()) {
          eob.getBillablePeriod().setStart(period.getStart());
        } else if (eob.getBillablePeriod().getStart().compareTo(period.getStart()) > 0) {
          eob.getBillablePeriod().setStart(period.getStart());

        }
      }

      if (end_date != null) {

        if (!eob.getBillablePeriod().hasEnd()) {
          eob.getBillablePeriod().setEnd(period.getEnd());
        } else if (eob.getBillablePeriod().getEnd().compareTo(period.getEnd()) < 0) {
          eob.getBillablePeriod().setEnd(period.getEnd());

        }
      }
    }
    return;
  }
  // #endregion GFE-Submit Operation Handlers

  // #region GFE-Submit Operation Bundle Add Elements

  private void addGfeBundleToAeobBundle(Bundle gfeBundle, Bundle aeobBundle, RequestDetails theRequestDetails) {
    logger.info("Adding GFE Bundle to AEOB Bundle");
    Bundle.BundleEntryComponent gfeBundleEntry = new Bundle.BundleEntryComponent();
    gfeBundleEntry.setFullUrl(theRequestDetails.getFhirServerBase() + "/Bundle/" + gfeBundle.getIdPart());
    gfeBundleEntry.setResource(gfeBundle);

    if (!gfeBundle.hasMeta()) {
      Meta gfeBundle_meta = new Meta();
      gfeBundle_meta.setVersionId("1");
      gfeBundle_meta.setLastUpdated(new Date());
      gfeBundle_meta.addProfile(PCT_GFE_BUNDLE_PROFILE);
      gfeBundle.setMeta(gfeBundle_meta);
    } else {
      Meta gfeBundle_meta = gfeBundle.getMeta();
      if (!gfeBundle_meta.hasProfile(PCT_GFE_BUNDLE_PROFILE)) {
        gfeBundle_meta.addProfile(PCT_GFE_BUNDLE_PROFILE);
      }
    }
    aeobBundle.addEntry(gfeBundleEntry);
  }

  private void addAeobToBundle(ExplanationOfBenefit aeob, Bundle aeobBundle, RequestDetails theRequestDetails) {
    logger.info("Adding AEOB to AEOB Bundle");
    Bundle.BundleEntryComponent aeobEntry = new Bundle.BundleEntryComponent();

    aeobEntry.setFullUrl(theRequestDetails.getFhirServerBase() + "/ExplanationOfBenefit/" + aeob.getIdElement().getIdPart());
    aeobEntry.setResource(aeob);
    aeobBundle.addEntry(aeobEntry);
  }

  private List<ExplanationOfBenefit.TotalComponent> addTotals(Claim claim, ExplanationOfBenefit aeob,
      double eligibleAmountPercent, double cost, Money eligibleAmount) {
    logger.info("Processing totals");
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
    logger.info("Processing claim items");
    List<ExplanationOfBenefit.ItemComponent> eobItems = new ArrayList<>();
    double coType = rand.nextInt(3);
    // List<Claim.ItemComponent> gfeClaimItems = claim.getItem();
    double cost = 0;
    for (Claim.ItemComponent claimItem : claim.getItem()) {
      cost = processItem(claim, eobItems, eligibleAmountPercent, coType, cost, claimItem);
    }
    aeob.setItem(eobItems);
    return cost;
  }

  private void addBenefitPeriod(ExplanationOfBenefit aeob) {
    Calendar cal = Calendar.getInstance();
    cal.set(Calendar.MONTH, 1);
    cal.set(Calendar.DAY_OF_YEAR, 1);
    aeob.getBenefitPeriod().setStart(cal.getTime());
    cal.set(Calendar.MONTH, 12);
    cal.set(Calendar.DAY_OF_MONTH, 31);
    aeob.getBenefitPeriod().setEnd(cal.getTime());
  }

  private void addExtensions(Bundle gfeBundle, Claim claim, Bundle.BundleEntryComponent providerEntry,
      ExplanationOfBenefit aeob, RequestDetails theRequestDetails) {

    Extension gfeReference = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeReference");
    gfeReference.setValue(new Reference("Bundle/" + gfeBundle.getId()));
    if (providerEntry != null) {
      IBaseResource providerResource = providerEntry.getResource();
      if (getClaimProvider(claim, gfeBundle) != null) {
        logger.info("Saving provider");
        switch(providerResource.fhirType()){
          case "Practitioner":
            thePractitionerDao.update((Practitioner)providerResource, theRequestDetails);
            break;
          case "PractitionerRole":
            thePractitionerRoleDao.update((PractitionerRole)providerResource,theRequestDetails);
            break;
          case "Organization":
            theOrganizationDao.update((Organization)providerResource, theRequestDetails);
            break;
        }

        // TODO Check this. Why writing a provider?
        // TODO This uses a generic writer which is not available (to my knowledge with
        // Dao). Need to look into whether this is even necessary
        //updateResource(providerResource);
      } else {
        logger.info("Unable to resolve Claim.provider reference in GFE Bundle");
      }
    }
    aeob.addExtension(gfeReference);

    Extension serviceExtension = new Extension(SERVICE_DESCRIPTION_EXTENSION);
    serviceExtension.setValue(new StringType("Example service"));
    aeob.addExtension(serviceExtension);

    return;
  }

  private void addProcessNote(ExplanationOfBenefit aeob) {
    // CodeableConcept cc = new CodeableConcept();
    // Coding c = new Coding();
    // c.setCode("disclaimer");
    // c.setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAEOBProcessNoteCS");
    // cc.addCoding(c);

    Extension processNoteClass = new Extension(
        "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/processNoteClass", new CodeableConcept(new Coding("disclaimer", "http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAEOBProcessNoteCS", "Disclaimer")));
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
    Bundle.BundleEntryComponent providerEntry = getClaimProvider(claim, gfeBundle);
    if (providerEntry != null) {
      logger.info("Adding provider");
      Reference provRef = claim.getProvider();
      // Add the provider resource to the bundle (this will ensure it's contained)
      gfeBundle.addEntry(providerEntry);  // Adding the provider to the Bundle as a contained resource
      aeob.setProvider(new Reference(provRef.getReference()));
    }
    return providerEntry;
  }

  private void addUniqueClaimIdentifier(ExplanationOfBenefit aeob) {
    List<Identifier> ids = aeob.getIdentifier();
    Identifier id = new Identifier();
    id.setSystem("urn:ietf:rfc:3986");
    // CodeableConcept idType = new CodeableConcept();
    // Coding c = new Coding();
    // c.setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTIdentifierType");
    // c.setCode("uc");
    // c.setDisplay("Unique Claim ID");

    //idType.getCoding().add(c);
    id.setType(new CodeableConcept(new Coding("uc", "http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTIdentifierType", "Unique Claim ID")));
    id.setValue("urn:uuid:" + UUID.randomUUID().toString());
    ids.add(id);
  }

  private void addBenefitBalance(ExplanationOfBenefit aeob) {

    BenefitBalanceComponent bb = aeob.addBenefitBalance();
    // bb.setCategory(createCodeableConcept("1",
    // "https://x12.org/codes/service-type-codes"));
    // bb.setUnit(createCodeableConcept("individual",
    // "http://terminology.hl7.org/CodeSystem/benefit-unit"));
    // bb.setTerm(createCodeableConcept("annual",
    // "http://terminology.hl7.org/CodeSystem/benefit-term"));
    bb.setCategory(new CodeableConcept(new Coding("https://x12.org/codes/service-type-codes", "1", "Medical Care")));
    bb.setUnit(new CodeableConcept(
        new Coding("http://terminology.hl7.org/CodeSystem/benefit-unit", "individual", "Individual")));
    bb.setTerm(
        new CodeableConcept(new Coding("http://terminology.hl7.org/CodeSystem/benefit-term", "annual", "Annual")));

    BenefitComponent financial = bb.addFinancial();
    financial
        // .setType(createCodeableConcept("allowed",
        // "http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTFinancialType"));
        .setType(new CodeableConcept(
            new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTFinancialType", "allowed", "Allowed")));
    Money allowed = new Money();
    allowed.setValue(5000);
    allowed.setCurrency("USD");
    financial.setAllowed(allowed);
    Money used = new Money();
    used.setValue(5000);
    used.setCurrency("USD");
    financial.setUsed(used);
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
    // RG: Should remove because only one memberliability adjudication slice is
    // allowed
    /*
     * else if (coType == 1) {
     * // coinsurance
     * adj4Category.addCoding()
     * .setSystem(
     * "http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAdjudicationCategoryCS")
     * .setCode("memberliability").setDisplay("Member Liability");
     * double costForItem = claimItem.getNet().getValue().doubleValue() * 0.2;
     * cost += costForItem;
     * amount2.setValue(costForItem);
     * }
     */

    eobItem4Adjudication.setCategory(adj4Category);
    eobItem4Adjudication.setAmount(amount2);
    eobItemAdjudications.add(eobItem4Adjudication);
    return cost;
  }

  // #endregion GFE-Submit Operation Bundle Add Elements

  // #region Response functions

  public void adjudicationErrorResponse(HttpServletRequest theRequest, HttpServletResponse theResponse, Bundle bundle)
      throws IOException {

    logger.info("Sending adjudication error response.");

    String adjudicationError = util.loadResource("templates/raw-adjudication-error.json");
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

  public void aeobCompleteResponse(HttpServletRequest theRequest, HttpServletResponse theResponse, Bundle bundle)
      throws IOException {

    logger.info("Sending AEOB complete response.");

    String aeobComplete = util.loadResource("templates/raw-aeob-complete.json");
    OperationOutcome oo = jparser.parseResource(OperationOutcome.class, aeobComplete);

    oo.setId("PCT-AEOB-Complete-Example-" + bundle.getIdElement().getIdPart());
    oo.getIssueFirstRep().setDiagnostics("AEOB processing for bundle " + bundle.getIdElement().getIdPart()
        + " is complete, the AEOB will be sent directly to the patient. No AEOB will be returned to the submitter.");

    String outputString = getOutputString(theRequest, theResponse, oo);

    try {
      theResponse.setStatus(418);
      theResponse.getWriter().write(outputString);
    } catch (Exception e) {
      handleError(theRequest, theResponse, e);
    }

    theResponse.getWriter().close();

  }

  public String getOutputString(HttpServletRequest theRequest, HttpServletResponse theResponse,
      IBaseResource resource) {

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
   * Sends an OperationOutcome response with the provided exception details and a
   * 500 status
   * 
   * @param theRequest  the request with the resource
   * @param theResponse the response
   * @param ex          exception to turn into an OperationOutcome 500 response
   * @throws IOException response writer exception
   */
  public void handleError(HttpServletRequest theRequest, HttpServletResponse theResponse, Exception ex)
      throws IOException {
    OperationOutcome oo = new OperationOutcome();
    OperationOutcome.OperationOutcomeIssueComponent ooIssue = new OperationOutcome.OperationOutcomeIssueComponent();
    ooIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
    ooIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
    CodeableConcept cc = new CodeableConcept();
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    ex.printStackTrace(pw);
    pw.close();
    cc.setText(sw.toString());
    ooIssue.setDetails(cc);
    oo.addIssue(ooIssue);
    String outputString = getOutputString(theRequest, theResponse, oo);
    theResponse.setStatus(500);
    theResponse.getWriter().write(outputString);
  }

  // #endregion Response functions

}
