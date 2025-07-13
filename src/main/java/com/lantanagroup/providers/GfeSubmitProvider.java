package com.lantanagroup.providers;

import com.lantanagroup.common.util;
import java.time.Instant;
import java.util.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IBaseResource;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Bundle.*;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.NoteComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.BenefitBalanceComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.BenefitComponent;
import ca.uhn.fhir.rest.api.server.RequestDetails;


// TODO Function documentation 
// TODO Refactor response handling (currently using servlett, but is that the current preferred method?)

public class GfeSubmitProvider {
  private static final String SERVICE_DESCRIPTION_EXTENSION = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/serviceDescription";
  private static final String OUT_OF_NETWORK_PROVIDER_INFO_EXTENSION = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/inNetworkProviderOptionsLink";
  private static final String DATA_ABSENT_REASON_EXTENSION = "http://hl7.org/fhir/StructureDefinition/data-absent-reason";
  private static final String PCT_GFE_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-bundle";
  private static final String PCT_GFE_SUMMARY_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-summary";
  private static final String PCT_GFE_MISSING_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-missing-bundle";
  private static final String PCT_AEOB_PACKET_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-packet";
  private static final String PCT_GFE_PACKET_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-packet";

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GfeSubmitProvider.class);
  private FhirContext theFhirContext;
  private IFhirResourceDao<Bundle> theBundleDao;
  private IFhirResourceDao<Practitioner> thePractitionerDao;
  private IFhirResourceDao<Organization> theOrganizationDao;
  private IFhirResourceDao<ExplanationOfBenefit> theExplanationOfBenefitDao;
  private IFhirResourceDao<Composition> theCompositionDao;
  private IFhirResourceDao<DocumentReference> theDocumentReferenceDao;
  private IFhirResourceDao<Patient> thePatientDao;

  private Integer simulatedDelaySeconds = 15;
  private IParser jparser;
  private IParser xparser;
  private Random rand;

  public GfeSubmitProvider(FhirContext ctx, DaoRegistry daoRegistry) {
    this.theFhirContext = ctx;
    theBundleDao = daoRegistry.getResourceDao(Bundle.class);
    thePractitionerDao = daoRegistry.getResourceDao(Practitioner.class);
    theOrganizationDao = daoRegistry.getResourceDao(Organization.class);
    theExplanationOfBenefitDao = daoRegistry.getResourceDao(ExplanationOfBenefit.class);
    theCompositionDao = daoRegistry.getResourceDao(Composition.class);
    theDocumentReferenceDao = daoRegistry.getResourceDao(DocumentReference.class);
    thePatientDao = daoRegistry.getResourceDao(Patient.class);

    jparser = this.theFhirContext.newJsonParser();
    jparser.setPrettyPrint(true);
    rand = new Random();

  }

  // #region Operations

  /**
   * Handles the $gfe-submit FHIR operation.
   * Receives a GFE Packet (FHIR Bundle), processes and adjudicates it, and generates an AEOB Packet in response.
   * Also creates a DocumentReference resource for searching document bundles.
   *
   * @param theBundleResource The input GFE Bundle with referenced resources.
   * @param theRequestDetails FHIR request context details.
   * @param theRequest        HTTP servlet request.
   * @param theResponse       HTTP servlet response.
   * @return 202 Accepted with Content-Location header for polling status or OperationOutcome on error.
   */
  @Operation(name = "$gfe-submit", type = Claim.class, manualResponse = true)
  public void gfeSubmit(
          @OperationParam(name = "resource", min = 1, max = 1, type = Bundle.class) Bundle theBundleResource,
          RequestDetails theRequestDetails,
          HttpServletRequest theRequest,
          HttpServletResponse theResponse) {

    logger.info("Received GFE Submit");

    try {
      // Validate if the input Bundle has the correct GFE packet profile and is of type 'document'. If not, return an error response.
      if (theBundleResource.getMeta() != null && theBundleResource.getMeta().hasProfile() && !theBundleResource.getMeta().hasProfile(PCT_GFE_PACKET_PROFILE)) {
        handleError(theRequest, theResponse, "Invalid profile. Input Bundle must be a GFE packet profile.", OperationOutcome.IssueType.INVALID);
        logger.info("Invalid profile. Input Bundle must be a GFE packet profile.");
        return;
      }
      if (theBundleResource.getType() != Bundle.BundleType.DOCUMENT) {
        handleError(theRequest, theResponse, "Input Bundle must be of type 'document' for GFE packet.", OperationOutcome.IssueType.INVALID);
        logger.info("Input Bundle must be of type 'document' for GFE packet.");
        return;
      }
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

      // The gfe bundle meta is missing after AEOB packet save. Adding profile manually before returning the response. // todo check if this is needed
      if (res.fhirType().equals("Bundle")) {
        if (!res.hasMeta()) {
          Meta gfeBundle_meta = new Meta();
          gfeBundle_meta.addProfile(PCT_GFE_BUNDLE_PROFILE);
          res.setMeta(gfeBundle_meta);
        } else {
          if (!res.getMeta().hasProfile(PCT_GFE_BUNDLE_PROFILE)) {
            res.getMeta().addProfile(PCT_GFE_BUNDLE_PROFILE);
          }
        }
      }

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
      handleError(theRequest, theResponse, e.getMessage(), OperationOutcome.IssueType.EXCEPTION);
    }

  }

  // #endregion Operations

  // #region GFE-Submit Operation Handlers
  /**
   * Parse the resource and create the new aeob packet. Send the initial bundle in
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
      logger.info("Converting GFE Packet to AEOB Packet");
      Bundle returnAEOBPacket = new Bundle();
      returnAEOBPacket.setType(BundleType.DOCUMENT);

      Meta aeob_packet_meta = new Meta();
      aeob_packet_meta.addProfile(PCT_AEOB_PACKET_PROFILE);
      returnAEOBPacket.setMeta(aeob_packet_meta);

      returnAEOBPacket.setIdentifier(new Identifier().setSystem(theRequestDetails.getFhirServerBase() + "/identifiers/bundle").setValue(UUID.randomUUID().toString()));
      returnAEOBPacket.setTimestamp(new Date());

      try {
        convertGFEPacketToAEOBPacket(theBundleResource, returnAEOBPacket, theRequestDetails);
      } catch (Exception e) {
        logger.info("Error converting GFE Packet to AEOB Packet: " + e.getMessage());
        handleError(theRequest, theResponse, "Error converting GFE Packet to AEOB Packet: " + e.getMessage(), OperationOutcome.IssueType.EXCEPTION);
        return;
      }

      logger.info("Saving AEOB Packet");
      // updateBundle(returnBundle);
      theBundleDao.create(returnAEOBPacket, theRequestDetails);

      outputString = theRequestDetails.getFhirServerBase() + "/Claim/$gfe-submit-poll-status?_bundleId=" + returnAEOBPacket.getIdElement().getIdPart();

      // Create and save the DocumentReference resource
      DocumentReference docRef = createAeobPacketDocumentReference(returnAEOBPacket, theBundleResource, theRequestDetails);
      if (docRef != null) {
        logger.info("Saving DocumentReference");
        theDocumentReferenceDao.create(docRef, theRequestDetails);
      }

      logger.info("Returning 202 with Content-Location header");
      theResponse.setStatus(202);
      theResponse.setHeader("Content-Location", outputString);
      theResponse.setContentType("text/plain");

    } catch (Exception ex) {
      handleError(theRequest, theResponse, ex.getMessage(), OperationOutcome.IssueType.EXCEPTION);
    }

  }

  /**
   * Updates the AEOB with institutional claim references from the GFE bundle.
   *
   * @param claim     the claim for the aeob
   * @param gfeBundle the bundle with all gfe resources
   * @param aeob      the aeob
   * @return the updated aeob
   */
  public ExplanationOfBenefit convertInstitutional(Claim claim, Bundle gfeBundle, ExplanationOfBenefit aeob) {
    aeob.getType().getCoding().get(0).setCode("institutional");
    aeob.getType().getCoding().get(0).setDisplay("Institutional");
    logger.info("Processing Institutional Claim");

    for (BundleEntryComponent e : gfeBundle.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) e.getResource();
      if (bundleEntry.fhirType().equals("Patient")) {
        Patient patient = (Patient) bundleEntry;
        logger.info("[" + (patient.getIdElement() != null ? patient.getIdElement().getValue() : "NULL") + "]");
        aeob.setPatient(new Reference(patient.getIdElement().getResourceType()+"/"+patient.getIdElement().getIdPart()));
      } else if (bundleEntry.fhirType().equals("Organization")) {
        Organization org = (Organization) bundleEntry;
        if (org.getType().get(0).getCoding().get(0).getCode().equals("pay")
                && (claim.getInsurer().getReference().contains(org.getId())
                || org.getId().contains(claim.getInsurer().getReference()))) {
          aeob.setInsurer(new Reference(org.getIdElement().getResourceType() + "/" + org.getIdElement().getIdPart()));
        } else if (claim.getProvider().getReference().contains("Organization") && (claim.getProvider().getReference().contains(org.getId())
                || org.getId().contains(claim.getProvider().getReference()))) {
          aeob.setProvider(new Reference(org.getIdElement().getResourceType() + "/" + org.getIdElement().getIdPart()));
        }
      } else if (bundleEntry.fhirType().equals("Coverage")) {
        Coverage cov = (Coverage) bundleEntry;
        aeob.getInsurance().get(0).setCoverage(new Reference(cov.getIdElement().getResourceType() + "/" + cov.getIdElement().getIdPart()));
      }
    }

    return aeob;
  }

  /**
   * Updates the AEOB with professional claim references from the GFE bundle.
   *
   * @param claim     the claim for the aeob
   * @param gfeBundle the bundle with all gfe resources
   * @param aeob      the aeob
   * @return the updated aeob
   */
  public ExplanationOfBenefit convertProfessional(Claim claim, Bundle gfeBundle, ExplanationOfBenefit aeob) {
    aeob.getType().getCoding().get(0).setCode("professional");
    aeob.getType().getCoding().get(0).setDisplay("Professional");
    logger.info("Processing Professional Claim");

    for (BundleEntryComponent e : gfeBundle.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) e.getResource();
      if (bundleEntry.fhirType().equals("Patient")) {
        Patient patient = (Patient) bundleEntry;
        logger.info("[" + (patient.getIdElement() != null ? patient.getIdElement().getValue() : "NULL") + "]");
        aeob.setPatient(new Reference(patient.getIdElement().getResourceType()+"/"+patient.getIdElement().getIdPart()));
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
          aeob.setInsurer(new Reference(org.getIdElement().getResourceType() + "/" + org.getIdElement().getIdPart()));
        } else if (claim.getProvider().getReference().contains("Organization")
                && (claim.getProvider().getReference().contains(org.getId())
                || org.getId().contains(claim.getProvider().getReference()))) {
          // Provider
          logger.info("Adding Provider with Organization");

          aeob.setProvider(new Reference(org.getIdElement().getResourceType() + "/" + org.getIdElement().getIdPart()));
        }
      } else if (bundleEntry.fhirType().equals("Coverage")) {
        Coverage cov = (Coverage) bundleEntry;
        aeob.getInsurance().get(0).setCoverage(new Reference(cov.getIdElement().getResourceType() + "/" + cov.getIdElement().getIdPart()));
      } else if (bundleEntry.fhirType().equals("Practitioner")) {
        Practitioner pr = (Practitioner) bundleEntry;
        if (claim.getProvider().getReference().contains("Practitioner")
                && (claim.getProvider().getReference().contains(pr.getId())
                || pr.getId().contains(claim.getProvider().getReference()))) {

          logger.info("Adding Provider by Practitioner");

          aeob.setProvider(new Reference(pr.getIdElement().getResourceType() + "/" + pr.getIdElement().getIdPart()));
        }
      }
    }

    return aeob;
  }

  public void convertGFEPacketToAEOBPacket(Bundle gfePacket, Bundle aeobPacket, RequestDetails theRequestDetails) {
    boolean isCollectionBundle = false;

    for (BundleEntryComponent gfePacketBundleEntry : gfePacket.getEntry()) {
      IBaseResource gfePacketBundleResource = (IBaseResource) gfePacketBundleEntry.getResource();

      // Claim type should be converted to AEOB. Todo check if this is still needed. Can a Claim resource appear as an entry inside gfePacket ?
      if (gfePacketBundleResource.fhirType().equals("Claim") && !isGFESummary((Claim) gfePacketBundleResource)) {
        claimToAEOB((Claim) gfePacketBundleResource, gfePacket, aeobPacket, theRequestDetails);
      }

      // If the entry is a bundle, process the claims in the bundle [ Bundle is either a GFE Bundle or a Missing Bundle]
      else if (gfePacketBundleResource.fhirType().equals("Bundle")) {
        Bundle innerGfeBundle = (Bundle) gfePacketBundleResource;
        for (BundleEntryComponent innerGfeBundleEntry : innerGfeBundle.getEntry()) {
          IBaseResource innerGfeBundleResource = (IBaseResource) innerGfeBundleEntry.getResource();
          if (innerGfeBundleResource.fhirType().equals("Claim") && !isGFESummary((Claim) innerGfeBundleResource)) {
            isCollectionBundle = true;
            claimToAEOB((Claim) innerGfeBundleResource, innerGfeBundle, aeobPacket, theRequestDetails);
          }
        }
      } else if (gfePacketBundleResource.fhirType().equals("Composition")) {
        // do nothing
      }

      // Add all other resources like patient , coverage , organization , practitioner to the return aeob packet
      else {
        aeobPacket.addEntry(gfePacketBundleEntry);
      }

      // TODO Make sure the individual aeob references are working right
      // TODO Make sure the individual AEOB network stuff is working
      // aeobBundle.addEntry(e);
    }

    // Ensure all referenced resources are added to aeobPacket before creating Composition
    if (isCollectionBundle) {
      for (BundleEntryComponent gfePacketEntry : gfePacket.getEntry()) {
        IBaseResource gfePacketResource = (IBaseResource) gfePacketEntry.getResource();
        if (gfePacketResource.fhirType().equals("Bundle")) {
          addGfeBundleToAeobPacket((Bundle) gfePacketResource, aeobPacket, theRequestDetails);
        }
      }
    }
    else {
      addGfeBundleToAeobPacket(gfePacket, aeobPacket, theRequestDetails);
    }

    // Save or update Practitioner, Organization, Patient and contained GFE bundles locally for searchability
    saveResourcesFromBundle(gfePacket, theRequestDetails);

    // Add AEOB Summary to AEOB Packet (ensure resource is added before referencing)
    addAEOBSummarytoAEOBPacket(gfePacket, aeobPacket, theRequestDetails);

    // Add AEOB Composition to AEOB Packet (after all referenced resources are present)
    addAEOBCompositiontoAEOBPacket(gfePacket, aeobPacket, theRequestDetails);

  }

  /**
   * Builds a DocumentReference for the generated AEOB packet. Searching for document bundles is done through this DocumentReference resource.
   * The DocumentReference attaches a link to the AEOB packet.
   */
  private DocumentReference createAeobPacketDocumentReference(Bundle aeobPacket, Bundle gfePacket, RequestDetails theRequestDetails) {
    DocumentReference docRef = null;
    try {
      docRef = new DocumentReference();
      //docRef.setId("PCT-AEOB-DocumentReference-" + UUID.randomUUID().toString());

      // Set meta/profile
      Meta meta = new Meta();
      meta.addProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-documentreference");
      docRef.setMeta(meta);

      // Set status, docStatus, type, category
      docRef.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
      docRef.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);
      docRef.setType(new CodeableConcept().addCoding(
        new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentTypeTemporaryTrialUse", "aeob-packet", "AEOB Packet")
      ));
      docRef.addCategory(
        new CodeableConcept().addCoding(
          new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentCategoryTemporaryTrialUse", "estimate", "Estimation Packet")
        )
      );

      // Add requestInitiationTime extension
      Extension requestInitiationTime = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/requestInitiationTime");
      requestInitiationTime.setValue(new InstantType(aeobPacket.getTimestamp()));
      docRef.addExtension(requestInitiationTime);

      // Copy gfeServiceLinkingInfo extension from GFE Composition if present
      for (BundleEntryComponent entry : gfePacket.getEntry()) {
        if (entry.getResource() instanceof Composition) {
          Composition comp = (Composition) entry.getResource();
          Extension gfeServiceLinkingInfo = comp.getExtensionByUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeServiceLinkingInfo");
          if (gfeServiceLinkingInfo != null) {
            docRef.addExtension(gfeServiceLinkingInfo.copy());
          }
          break;
        }
      }

      // Set subject (Patient reference)
      for (BundleEntryComponent entry : gfePacket.getEntry()) {
        if (entry.getResource() instanceof Patient) {
          logger.info("DocRef Subject Patient/" + entry.getResource().getIdElement().getIdPart());
          String idRaw = entry.getResource().getIdElement().getValue();
          String uuid = idRaw.startsWith("urn:uuid:") ? idRaw.substring("urn:uuid:".length()) : null;
          String patientId = idRaw.startsWith("urn:")
                  ? uuid
                  : entry.getResource().getIdElement().getIdPart();
            docRef.setSubject(new Reference("Patient/" + patientId));
          break;
        }
      }

      // Set date
      docRef.setDate(new Date());

      // Add Authors (Organization|Practitioner) reference to the payer and all GFE Packet authors
      Set<String> authorRefs = new HashSet<>();
      for (BundleEntryComponent entry : gfePacket.getEntry()) {
        if (entry.getResource() instanceof Organization || entry.getResource() instanceof Practitioner) {
          String ref = entry.getResource().getIdElement().getResourceType() + "/" + entry.getResource().getIdElement().getIdPart();
          if (authorRefs.add(ref)) {
            logger.info("DocRef Author added: " + ref);
            docRef.addAuthor(new Reference(ref));
          }
        }
      }

      // Add content (Attachment with AEOB packet URL)
      DocumentReference.DocumentReferenceContentComponent content = new DocumentReference.DocumentReferenceContentComponent();
      Attachment attachment = new Attachment();
      attachment.setContentType("application/fhir+json");
      logger.info("Attachment Url " + theRequestDetails.getFhirServerBase() + "/Bundle/" + aeobPacket.getIdElement().getIdPart());
      attachment.setUrl(theRequestDetails.getFhirServerBase() + "/Bundle/" + aeobPacket.getIdElement().getIdPart());
      content.setAttachment(attachment);
      content.setFormat(new Coding()
        .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentTypeTemporaryTrialUse")
        .setCode("pct-aeob-packet"));
      docRef.addContent(content);

    } catch (Exception e) {
      logger.info("Error creating AEOB DocumentReference: " + e.getMessage());
      e.printStackTrace();
    }
    return docRef;
  }

  private void saveResourcesFromBundle(Bundle theBundleResource, RequestDetails theRequestDetails) {
    Set<String> processedResourceIds = new HashSet<>();
    logger.info("Saving the GFE packet Resources");

    for (Bundle.BundleEntryComponent entry : theBundleResource.getEntry()) {
      Resource resource = entry.getResource();

      if (resource == null ||
              !(resource instanceof Practitioner ||
                      resource instanceof Organization ||
                      resource instanceof Bundle ||
                      resource instanceof Patient)) continue;

      String logicalId = resource.getIdElement().getIdPart();

      // Fix for error HAPI-0989 - Remove _history from resource ID before update if exists
      String fullId = resource.getIdElement().getValue();
      // Log the full resource ID (including _history if present for debugging)
      logger.info("----Full Resource ID: " + fullId);
      logger.info("LogicalId: " + logicalId);
      if (fullId != null && fullId.contains("/_history/")) {
        // Strip _history from ID for update
        String baseId = fullId.substring(0, fullId.indexOf("/_history/"));
        resource.setId(baseId.substring(baseId.lastIndexOf('/') + 1));
        logicalId = resource.getIdElement().getIdPart();
        logger.info("Removed _history, new Resource ID: " + resource.getIdElement().getValue());
      }
      if (logicalId != null && logicalId.startsWith("urn:uuid:")) {
        logicalId = logicalId.substring("urn:uuid:".length());
        // Set the resource's ID to the bare UUID for persistence
        resource.setId(logicalId);
        logger.info("Removed urn:uuid:, new LogicalId: " + resource.getIdElement().getIdPart());
      }/* else {
        resource.setId(logicalId);
      }*/

      String resourceType = resource.getResourceType().toString();
      if (logicalId != null) {
        String uniqueKey = resourceType + "/" + logicalId;
        if (!processedResourceIds.add(uniqueKey)) {
          continue;
        }
      }

      IFhirResourceDao dao = null;
      if (resource instanceof Practitioner) {
        dao = thePractitionerDao;
      } else if (resource instanceof Organization) {
        dao = theOrganizationDao;
      } else if (resource instanceof Patient) {
        dao = thePatientDao;
      } else if (resource instanceof Bundle) {
        dao = theBundleDao;
      }

      if (dao != null) {
         dao.update(resource, theRequestDetails);
      }
    }
  }

  public void claimToAEOB(Claim claim, Bundle gfeBundle, Bundle aeobPacket, RequestDetails theRequestDetails) {
    // load the base aeob
    String eob = util.loadResource("templates/raw-aeob.json");
    ExplanationOfBenefit aeob = jparser.parseResource(ExplanationOfBenefit.class, eob);
    aeob.setCreated(new Date());
    // set the aeob values based on the gfe
    convertGFEtoAEOB(gfeBundle, claim, aeob, aeobPacket, theRequestDetails);
  }


  public boolean isGFESummary(Claim claim) {
    if (claim.hasMeta() && claim.getMeta().hasProfile() && claim.getMeta().getProfile().get(0).getValue().equals(PCT_GFE_SUMMARY_PROFILE)) {
      return true;
    }
    return false;
  }

  /**
   * Modify the aeob with new extensions and all the resources from the gfe to the
   * aeob and add all
   * the resources to the aeobPacket
   *
   * @param gfeBundle  the gfe bundle
   * @param aeob       the aeob to modify
   * @param aeobPacket the bundle to return
   * @return the complete aeob packet
   */
  public Bundle convertGFEtoAEOB(Bundle gfeBundle, Claim claim, ExplanationOfBenefit aeob, Bundle aeobPacket,
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

      theExplanationOfBenefitDao.create(aeob, theRequestDetails);
      addAeobToPacket(aeob, aeobPacket, theRequestDetails);
      if (claim.getMeta().getProfile().get(0).getValue()
              .equals("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-institutional")) {
        convertInstitutional(claim, gfeBundle, aeob);
      } else {
        convertProfessional(claim, gfeBundle, aeob);
      }

      logger.info("Updating AEOB");
      // TODO Check why saving earlier and updating now
      theExplanationOfBenefitDao.update(aeob, theRequestDetails);
    } catch (Exception e) {
      logger.info("Error: " + e.getMessage());
    }
    return aeobPacket;
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
   * Add AEOB Composition to AEOB Packet and return the Composition resource.
   */
  public Bundle addAEOBCompositiontoAEOBPacket(Bundle gfePacket, Bundle aeobPacket, RequestDetails theRequestDetails) {
    logger.info("Creating AEOB Composition");
    try {
      Composition aeobComposition = new Composition();
      Meta aeobCompositionMeta = new Meta();
      aeobCompositionMeta.setVersionId("1");
      aeobCompositionMeta.setLastUpdated(new Date());
      aeobCompositionMeta.addProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-composition");
      aeobComposition.setMeta(aeobCompositionMeta);
      //String compId = "Composition-" + UUID.randomUUID();
      //aeobComposition.setId(new IdType("Composition", compId));
      aeobComposition.setStatus(Composition.CompositionStatus.FINAL);
      aeobComposition.setType(new CodeableConcept(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentTypeTemporaryTrialUse", "aeob-packet", "AEOB Packet")));
      aeobComposition.addCategory(
              new CodeableConcept().addCoding(
                      new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentCategoryTemporaryTrialUse", "estimate", "Estimation Packet")
              )
      );
      aeobComposition.setDate(new Date());
      aeobComposition.setSection(new ArrayList<Composition.SectionComponent>());
      // Add identifier to the Composition. This identifier is used to track the AEOB Composition
      aeobComposition.setIdentifier(
              new Identifier()
                      .setSystem(theRequestDetails.getFhirServerBase() + "/identifiers/composition")
                      .setValue(UUID.randomUUID().toString())
      );
      // Track unique author references
      Set<String> authorRefs = new HashSet<>();
      // Add a section for AEOB Summary and AEOB section
      for (BundleEntryComponent aeobPacketEntry : aeobPacket.getEntry()) {
        Resource aeobPacketEntryResource = aeobPacketEntry.getResource();
        if (aeobPacketEntryResource instanceof ExplanationOfBenefit) {
          ExplanationOfBenefit eob = (ExplanationOfBenefit) aeobPacketEntryResource;
          if (eob.getMeta() != null && eob.getMeta().hasProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-summary")) {
            Composition.SectionComponent section = createSection("aeob-summary-section", "AEOB Summary", eob);
            aeobComposition.addSection(section);
          } else if (eob.getMeta() != null && eob.getMeta().hasProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob")) {
            Composition.SectionComponent section = createSection("aeob-section", "AEOB Section", eob);
            aeobComposition.addSection(section);
            if (eob.hasInsurer() && eob.getInsurer().hasReference()) {
              String insurerRef = eob.getInsurer().getReference();
              if (authorRefs.add(insurerRef)) { // Add author only if not already added
                aeobComposition.addAuthor(new Reference(insurerRef));
              }
            }
            if (eob.hasProvider() && eob.getProvider().hasReference()) {
              String providerRef = eob.getProvider().getReference();
              if (authorRefs.add(providerRef)) { // Add author only if not already added
                aeobComposition.addAuthor(new Reference(providerRef));
              }
            }
          }
        }
      }

      // Add a section for each GFE in the bundle
      for (BundleEntryComponent gfePacketEntry : gfePacket.getEntry()) {
        Resource gfePacketEntryResource = gfePacketEntry.getResource();
        if (gfePacketEntryResource instanceof Bundle && gfePacketEntryResource.getMeta() != null
                && gfePacketEntryResource.getMeta().hasProfile() && gfePacketEntryResource.getMeta().hasProfile(PCT_GFE_BUNDLE_PROFILE)
                && !gfePacketEntryResource.getMeta().hasProfile(PCT_GFE_MISSING_BUNDLE_PROFILE)) {
          Bundle gfeBundle = (Bundle) gfePacketEntryResource;
          Composition.SectionComponent section = createSection("gfe-section", "GFE Section", gfeBundle);

          // Add authors from the GFE bundle to the section
          Set<String> sectionAuthorRefs = new HashSet<>();
          for (BundleEntryComponent gfeBundleEntry : gfeBundle.getEntry()) {
            Resource gfeBundleEntryResource = gfeBundleEntry.getResource();
            if (gfeBundleEntryResource instanceof Organization) { //Associated GFE author (GFE Contributor)
              Organization org = (Organization) gfeBundleEntryResource;
              if (org.hasType() && org.getType().get(0).hasCoding() &&
                      !"pay".equals(org.getType().get(0).getCoding().get(0).getCode())) {
                String ref = gfeBundleEntryResource.getIdElement().getResourceType() + "/" + gfeBundleEntryResource.getIdElement().getIdPart();
                if (sectionAuthorRefs.add(ref)) {
                  section.addAuthor(new Reference(ref));
                }
              }
            } else if (gfeBundleEntryResource instanceof Practitioner) { //Associated GFE author (GFE Contributor)
              String ref = gfeBundleEntryResource.getIdElement().getResourceType() + "/" + gfeBundleEntryResource.getIdElement().getIdPart();
              if (sectionAuthorRefs.add(ref)) {
                section.addAuthor(new Reference(ref));
              }
            }
          }
          aeobComposition.addSection(section);
        } else if (gfePacketEntryResource instanceof Patient) {
          if (aeobComposition.getSubject() == null || aeobComposition.getSubject().isEmpty()) {
            Patient patient = (Patient) gfePacketEntryResource;
            String logicalId = patient.getIdElement().getIdPart();
            if (logicalId != null && logicalId.startsWith("urn:uuid:")) {
              logicalId = logicalId.substring("urn:uuid:".length());
            }
            aeobComposition.setSubject(new Reference("Patient/" + logicalId));
            aeobComposition.setTitle("Advanced Explanation of Benefit Packet for " + patient.getNameFirstRep().getText() + " - " + new Date().toString());
          }
        }
      }

      logger.info("Saving AEOB Composition");
      theCompositionDao.create(aeobComposition, theRequestDetails);

      // Add the AEOB Composition to the AEOB Packet
      BundleEntryComponent aeobCompositionEntry = new BundleEntryComponent();
      aeobCompositionEntry.setId(aeobComposition.getIdElement().getIdPart());
      aeobCompositionEntry.setFullUrl(theRequestDetails.getFhirServerBase() + "/Composition/" + aeobComposition.getIdElement().getIdPart());
      aeobCompositionEntry.setResource(aeobComposition);

      // Ensure AEOB Composition is the first resource in the AEOB packet
      aeobPacket.getEntry().add(0, aeobCompositionEntry);
    } catch (Exception e) {
      logger.info("Error creating AEOB Composition: " + e.getMessage());
      throw new RuntimeException("Error creating AEOB Composition "+e.getMessage(), e);
    }
    return aeobPacket;
  }

  private Composition.SectionComponent createSection(String code, String display, Resource resource) {
    Composition.SectionComponent section = new Composition.SectionComponent();
    section.setCode(new CodeableConcept(new Coding(
            "http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentSection",
            code, display)));
    section.addEntry(new Reference(resource.getIdElement().getResourceType() + "/" + resource.getIdElement().getIdPart()));
    return section;
  }

  /**
   * Add AEOB Summary EOB to the AOEB Packet
   *
   * @param aeobPacket the bundle to add to summary to
   * @return the complete aeob packet
   */
  public Bundle addAEOBSummarytoAEOBPacket(Bundle gfeBundle, Bundle aeobPacket, RequestDetails theRequestDetails) {
    logger.info("Summarizing AEOB Packet");
    try {
      ExplanationOfBenefit aeob_summary = new ExplanationOfBenefit();
      Meta aeob_summary_meta = new Meta();
      aeob_summary_meta.setVersionId("1");
      aeob_summary_meta.setLastUpdated(new Date());
      aeob_summary_meta.addProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-summary");
      aeob_summary.setMeta(aeob_summary_meta);

      // Ensure the summary EOB has a unique and consistent ID
      //String summaryId = "Summary-" + UUID.randomUUID();
      //aeob_summary.setId(summaryId);

      Extension serviceExtension = new Extension(SERVICE_DESCRIPTION_EXTENSION);
      serviceExtension.setValue(new StringType(
              "Example service"));
      aeob_summary.addExtension(serviceExtension);

      Extension outOfNetworkProviderInfo = new Extension(OUT_OF_NETWORK_PROVIDER_INFO_EXTENSION);
      outOfNetworkProviderInfo.setValue(new UrlType("http://example.com/out-of-network.html"));
      aeob_summary.addExtension(outOfNetworkProviderInfo);

      aeob_summary.setStatus(ExplanationOfBenefitStatus.ACTIVE);

      aeob_summary.setType(new CodeableConcept(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTEstimateTypeSummaryCSTemporaryTrialUse", "estimate-summary", "Estimate Summary")));

      aeob_summary.setUse(ExplanationOfBenefit.Use.PREDETERMINATION);

      aeob_summary.setCreated(aeob_summary_meta.getLastUpdated());

      Extension providerAbsentReason = new Extension(DATA_ABSENT_REASON_EXTENSION);
      //Coding darCoding = new Coding();
      //darCoding.setCode("not-applicable");
      providerAbsentReason.setValue(new CodeType("not-applicable"));
      aeob_summary.getProvider().addExtension(providerAbsentReason);

      aeob_summary.setOutcome(ExplanationOfBenefit.RemittanceOutcome.COMPLETE);

      Map<String, ExplanationOfBenefit.TotalComponent> totals_map = new HashMap<String, ExplanationOfBenefit.TotalComponent>();

      for (BundleEntryComponent e : aeobPacket.getEntry()) {
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
          for (BundleEntryComponent gfe_bundle_entry : gfeBundle.getEntry()) {
            IBaseResource gfe_entry = (IBaseResource) gfe_bundle_entry.getResource();
            if (gfe_entry.fhirType().equals("Claim") && !isGFESummary((Claim) gfe_entry)) {
              Claim gfe_claim = (Claim) gfe_entry;
              processSummaryBillablePeriod(gfe_claim, aeob_summary);
            } else if (gfe_entry instanceof Bundle) {
              Bundle gfe_bundle = (Bundle) gfe_entry;
              for (BundleEntryComponent entry : gfe_bundle.getEntry()) {
                IBaseResource entry_resource = (IBaseResource) entry.getResource();
                if (entry_resource.fhirType().equals("Claim") && !isGFESummary((Claim) entry_resource)) {
                  Claim gfe_claim = (Claim) entry_resource;
                  processSummaryBillablePeriod(gfe_claim, aeob_summary);
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
            if (total.hasCategory() && total.getCategory().hasCoding()) {
              for (Coding coding : total.getCategory().getCoding()) {
                if (coding.hasSystem()
                        && coding.getSystem().equals("http://terminology.hl7.org/CodeSystem/adjudication")) {
                  if (!totals_map.containsKey(coding.getCode())) {
                    CodeableConcept category = total.getCategory();
                    totals_map.put(coding.getCode(), new ExplanationOfBenefit.TotalComponent(category, new Money().setValue(BigDecimal.ZERO)));
                  }
                  totals_map.get(coding.getCode()).getAmount()
                          .setValue(totals_map.get(coding.getCode()).getAmount().getValue().add(total.getAmount().getValue()).doubleValue());
                }
              }
            }
          }
          // Find Lowest balance and save or find the top benefit ballance and subtract
          // the totals?
          aeob_summary.setBenefitBalance(addAEOBSummaryBenefitBalance(aeobPacket));

        }

      }
      for (String key : totals_map.keySet()) {
        aeob_summary.addTotal(totals_map.get(key));
      }
      logger.info("Saving AEOB Summary");
      theExplanationOfBenefitDao.create(aeob_summary, theRequestDetails);

      // Add AEOB Summary to AEOB Packet
      Bundle.BundleEntryComponent summary_aeobPacketEntry = new Bundle.BundleEntryComponent();
      summary_aeobPacketEntry.setFullUrl(theRequestDetails.getFhirServerBase() + "/ExplanationOfBenefit/" + aeob_summary.getIdElement().getIdPart());
      summary_aeobPacketEntry.setId(aeob_summary.getIdElement().getIdPart());
      summary_aeobPacketEntry.setResource(aeob_summary);
      aeobPacket.getEntry().add(1, summary_aeobPacketEntry);
      logger.info(summary_aeobPacketEntry.getFullUrl());
    } catch (Exception e) {
      logger.info("Error: " + e.getMessage());
    }
    return aeobPacket;
  }

  private void processSummaryBillablePeriod(Claim claim, ExplanationOfBenefit aeobSummary) {
    for (Claim.ItemComponent claim_item : claim.getItem()) {
      if (claim_item.hasServicedDateType()) {
        UpdateSummaryBillablePeriod(aeobSummary, claim_item.getServicedDateType().getValue(),
                claim_item.getServicedDateType().getValue());
      } else if (claim_item.hasServicedPeriod()) {
        UpdateSummaryBillablePeriod(aeobSummary, claim_item.getServicedPeriod().getStart(),
                claim_item.getServicedPeriod().getEnd());
      }
    }
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

  private void addGfeBundleToAeobPacket(Bundle gfeBundle, Bundle aeobPacket, RequestDetails theRequestDetails) {
    logger.info("Adding GFE Bundle "+gfeBundle.getIdPart()+" to AEOB Packet");

    // Add GFE Bundle to AEOB Packet
    Bundle.BundleEntryComponent gfeBundleEntry = new BundleEntryComponent();
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
    aeobPacket.addEntry(gfeBundleEntry);
  }

  private void addAeobToPacket(ExplanationOfBenefit aeob, Bundle aeobPacket, RequestDetails theRequestDetails) {
    logger.info("Adding AEOB to AEOB Packet");
    Bundle.BundleEntryComponent aeobEntry = new Bundle.BundleEntryComponent();

    aeobEntry.setFullUrl(theRequestDetails.getFhirServerBase() + "/ExplanationOfBenefit/" + aeob.getIdElement().getIdPart());
    aeobEntry.setResource(aeob);
    aeobPacket.addEntry(aeobEntry);
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
    gfeReference.setValue(new Reference("Bundle/" + gfeBundle.getIdElement().getIdPart()));
    if (providerEntry != null) {
      IBaseResource providerResource = providerEntry.getResource();
      if (getClaimProvider(claim, gfeBundle) != null) {
        logger.info("Saving provider");
        switch (providerResource.fhirType()) {
          case "Practitioner":
            thePractitionerDao.update((Practitioner) providerResource, theRequestDetails);
            break;
          case "Organization":
            theOrganizationDao.update((Organization) providerResource, theRequestDetails);
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

    Extension outOfNetworkProviderInfo = new Extension(OUT_OF_NETWORK_PROVIDER_INFO_EXTENSION);
    outOfNetworkProviderInfo.setValue(new UrlType("http://example.com/out-of-network.html"));
    aeob.addExtension(outOfNetworkProviderInfo);

    return;
  }

  private void addProcessNote(ExplanationOfBenefit aeob) {
    // CodeableConcept cc = new CodeableConcept();
    // Coding c = new Coding();
    // c.setCode("disclaimer");
    // c.setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAEOBProcessNoteCS");
    // cc.addCoding(c);

    Extension processNoteClass = new Extension(
            "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/processNoteClass", new CodeableConcept(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTAEOBProcessNoteCS", "disclaimer", "Disclaimer")));
    NoteComponent note = aeob.addProcessNote();
    note.addExtension(processNoteClass);
    note.setText("processNote disclaimer text");
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
    id.setType(new CodeableConcept(new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTIdentifierType", "uc", "Unique Claim ID")));
    id.setValue("urn:uuid:" + UUID.randomUUID().toString());
    ids.add(id);
  }

  private List<BenefitBalanceComponent> addAEOBSummaryBenefitBalance(Bundle aeobPacket) {
    Map<String, BenefitBalanceComponent> summaryBenefitBalanceCategoryMap = new HashMap();

    // Traverse all AEOB's to summarize BenefitBalance
    for (BundleEntryComponent entry : aeobPacket.getEntry()) {
      IBaseResource bundleEntry = (IBaseResource) entry.getResource();
      if (bundleEntry.fhirType().equals("ExplanationOfBenefit")) {

        ExplanationOfBenefit aeob = (ExplanationOfBenefit) bundleEntry;
        // Loop through all benefit balance
        for (BenefitBalanceComponent benefitBalance : aeob.getBenefitBalance()) {
          String category = benefitBalance.getCategory().getText();
          BigDecimal remainingBalance = null;

          BenefitBalanceComponent summaryBenefitBalance = summaryBenefitBalanceCategoryMap.get(category);
          if (summaryBenefitBalance == null) {
            summaryBenefitBalance = new BenefitBalanceComponent();
            summaryBenefitBalance.setUnit(benefitBalance.getUnit());
            summaryBenefitBalance.setTerm(benefitBalance.getTerm());
            summaryBenefitBalance.setCategory(benefitBalance.getCategory());
            summaryBenefitBalanceCategoryMap.put(category, summaryBenefitBalance);
          }
          // Loop through all benefit balance
          for (BenefitComponent benefitComponent : benefitBalance.getFinancial()) {

            // Check if this already exists
            BenefitComponent summaryBenefitComponent = null;
            for (BenefitComponent existingSummaryBenefitComponent : summaryBenefitBalance.getFinancial()) {
              if (existingSummaryBenefitComponent.equals(benefitComponent)) {
                summaryBenefitComponent = existingSummaryBenefitComponent;
                break;
              }
            }
            if (summaryBenefitComponent == null) {
              summaryBenefitComponent = new BenefitComponent();
              summaryBenefitComponent.setType(benefitComponent.getType());
              summaryBenefitComponent.setAllowed(new Money().setValue(BigDecimal.ZERO));
              summaryBenefitComponent.setUsed(new Money().setValue(BigDecimal.ZERO));
            }
            if (benefitComponent.getAllowedMoney() != null && benefitComponent.getAllowedMoney().getValue() != null) {
              summaryBenefitComponent.setAllowed(new Money().setValue(summaryBenefitComponent.getAllowedMoney().getValue().add(benefitComponent.getAllowedMoney().getValue())).setCurrency("USD"));
            }
            if (benefitComponent.getUsedMoney() != null && benefitComponent.getUsedMoney().getValue() != null) {
              summaryBenefitComponent.setUsed(new Money().setValue(summaryBenefitComponent.getUsedMoney().getValue().add(benefitComponent.getUsedMoney().getValue())).setCurrency("USD"));
            }
            remainingBalance = summaryBenefitComponent.getAllowedMoney().getValue().subtract(summaryBenefitComponent.getUsedMoney().getValue());
            Extension remainingBalanceExtension = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/remaining-benefit");
            remainingBalanceExtension.setValue(new Money().setValue(remainingBalance).setCurrency("USD"));
            summaryBenefitComponent.addExtension(remainingBalanceExtension);
            if (!summaryBenefitBalance.getFinancial().contains(summaryBenefitComponent)) {
              summaryBenefitBalance.addFinancial(summaryBenefitComponent);
            }
          }
        }
      }
    }
    return summaryBenefitBalanceCategoryMap.values().stream().collect(Collectors.toList());
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
      handleError(theRequest, theResponse, e.getMessage(), OperationOutcome.IssueType.EXCEPTION);
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
      handleError(theRequest, theResponse, e.getMessage(), OperationOutcome.IssueType.EXCEPTION);
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
   * 400/500 status code.
   *
   * @param theRequest  HTTP request with the resource
   * @param theResponse HTTP response
   * @param message     error message to turn into an OperationOutcome response
   * @param issueType   OperationOutcome.IssueType to use (e.g., INVALID, EXCEPTION)
   * @throws IOException response writer exception
   */
  public void handleError(HttpServletRequest theRequest, HttpServletResponse theResponse, String message, OperationOutcome.IssueType issueType)
          throws IOException {
    OperationOutcome oo = new OperationOutcome();
    OperationOutcome.OperationOutcomeIssueComponent ooIssue = new OperationOutcome.OperationOutcomeIssueComponent();
    ooIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
    ooIssue.setCode(issueType);
    CodeableConcept cc = new CodeableConcept();
    cc.setText(message != null ? message : "An unexpected error occurred.");
    ooIssue.setDetails(cc);
    oo.addIssue(ooIssue);
    String outputString = getOutputString(theRequest, theResponse, oo);
    // Use 400 for validation errors, 500 for server errors
    int status = (issueType == OperationOutcome.IssueType.INVALID) ? 400 : 500;
    theResponse.setStatus(status);
    theResponse.getWriter().write(outputString);
  }

  // #endregion Response functions

}

