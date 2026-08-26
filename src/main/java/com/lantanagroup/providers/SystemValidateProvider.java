package com.lantanagroup.providers;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.ValidationModeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.OperationOutcome;

public class SystemValidateProvider {

  private final DaoRegistry daoRegistry;

  public SystemValidateProvider(DaoRegistry daoRegistry) {
    this.daoRegistry = daoRegistry;
  }

  @SuppressWarnings("unchecked")
  @Operation(name = "$validate", idempotent = true)
  public OperationOutcome validate(
      @OperationParam(name = "resource", min = 0, max = 1, type = IBaseResource.class) IBaseResource theResource,
      @OperationParam(name = "mode", min = 0, max = 1) String theMode,
      @OperationParam(name = "profile", min = 0, max = 1) String theProfile,
      RequestDetails theRequestDetails) {

    ValidationModeEnum mode = theMode != null ? ValidationModeEnum.forCode(theMode) : null;

    IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(theResource);
    MethodOutcome outcome = dao.validate(theResource, null, null, null, mode, theProfile, theRequestDetails);

    return (OperationOutcome) outcome.getOperationOutcome();
  }
}
