package ca.uhn.fhir.jpa.starter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.servlet.http.HttpServletRequest;


import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CapabilityStatement.*;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import ca.uhn.fhir.rest.annotation.Metadata;
import ca.uhn.fhir.jpa.provider.JpaCapabilityStatementProvider;

import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.context.support.IValidationSupport;

public class MetadataProvider extends JpaCapabilityStatementProvider {
  //BaseJpaRestfulServer,IFhirSystemDao,DaoConfig,ISearchParamRegistry,IValidationSupport
  MetadataProvider(BaseJpaRestfulServer theRestfulServer, IFhirSystemDao theSystemDao, DaoConfig theDaoConfig,ISearchParamRegistry spr, IValidationSupport ivs) {
    super(theRestfulServer, theSystemDao, theDaoConfig, spr, ivs);
    // setCache(false);
  }

  @Metadata
  public CapabilityStatement getServerConformance(HttpServletRequest theRequest, RequestDetails details) {
    System.out.println("Generating statement");
    CapabilityStatement metadata = (CapabilityStatement) super.getServerConformance(theRequest, details);
    metadata.setTitle("Da Vinci PCT Payer Reference Implementation");
    metadata.setStatus(PublicationStatus.DRAFT);
    metadata.setExperimental(true);
    metadata.setPublisher("Da Vinci");

    Calendar calendar = Calendar.getInstance();
    calendar.set(2019, 8, 5, 0, 0, 0);
    metadata.setDate(calendar.getTime());

    CapabilityStatementSoftwareComponent software = new CapabilityStatementSoftwareComponent();
    software.setName("https://github.com/HL7-DaVinci/test-pct-payer");
    metadata.setSoftware(software);

    metadata.addImplementationGuide("https://build.fhir.org/ig/HL7/davinci-pct/");

    updateRestComponents(metadata.getRest());
    return metadata;
  }

  private void updateRestComponents(List<CapabilityStatementRestComponent> originalRests) {
    for(CapabilityStatementRestComponent rest : originalRests) {
      List<CapabilityStatementRestResourceComponent> resources = rest.getResource();
      for(CapabilityStatementRestResourceComponent resource : resources) {
        if (resource.getType().equals("Coverage")) {
          resource.setProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-coverage");
        } else if (resource.getType().equals("Bundle")) {
          List<CanonicalType> supportedProfiles = new ArrayList<>();
          supportedProfiles.add(new CanonicalType("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-bundle"));
          supportedProfiles.add(new CanonicalType("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-bundle-institutional"));
          supportedProfiles.add(new CanonicalType("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-bundle-professional"));
          resource.setSupportedProfile(supportedProfiles);
        } else if (resource.getType().equals("Claim")) {
          List<CanonicalType> supportedProfiles = new ArrayList<>();
          supportedProfiles.add(new CanonicalType("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-professional"));
          supportedProfiles.add(new CanonicalType("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/pct-gfe-Institutional"));
          resource.setSupportedProfile(supportedProfiles);
        } else if (resource.getType().equals("ExplanationOfBenefit")) {
          resource.setProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob");
        } else if (resource.getType().equals("Location")) {
          resource.setProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-location");
        } else if (resource.getType().equals("Organization")) {
          resource.setProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-organization");
        } else if (resource.getType().equals("Patient")) {
          resource.setProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-patient");
        } else if (resource.getType().equals("Practitioner")) {
          resource.setProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-practitioner");
        } else if (resource.getType().equals("PractitionerRole")) {
          resource.setProfile("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-practitionerrole");
        }
      }
    }
  }
}
