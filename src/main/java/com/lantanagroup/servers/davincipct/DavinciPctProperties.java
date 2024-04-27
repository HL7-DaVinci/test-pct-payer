package com.lantanagroup.servers.davincipct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.lantanagroup.common.ServerProperties;

@Configuration
@ConfigurationProperties(prefix = "davincipct")
public class DavinciPctProperties extends ServerProperties {
  
}
