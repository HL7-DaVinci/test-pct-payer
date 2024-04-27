package com.lantanagroup.providers;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import org.hl7.fhir.r4.model.*;

public class GfeRetrieveOperation {

  @Operation(name = "$gfe-retrieve")
  public Bundle gfeRetrieve(
    @OperationParam(name = "request", min = 1, max = 1, type = Reference.class) Reference theRequest
  ) {
    // TODO: Implement operation $gfe-retrieve
    throw new NotImplementedOperationException("Operation $gfe-retrieve is not implemented");
    
    // Bundle retVal = new Bundle();
    // return retVal;
    
  }

  
}
