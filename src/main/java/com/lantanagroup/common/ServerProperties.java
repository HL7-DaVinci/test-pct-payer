package com.lantanagroup.common;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import ca.uhn.fhir.jpa.starter.AppProperties;
import lombok.Getter;
import lombok.Setter;

public class ServerProperties extends AppProperties {
  
  @Getter @Setter
  private DataSourceProperties datasource;

}
