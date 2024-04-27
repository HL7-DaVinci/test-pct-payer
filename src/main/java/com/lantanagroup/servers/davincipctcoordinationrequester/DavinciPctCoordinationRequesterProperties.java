package com.lantanagroup.servers.davincipctcoordinationrequester;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.lantanagroup.common.ServerProperties;

@Configuration
@ConfigurationProperties(prefix = "davincipctcoordinationrequester")
public class DavinciPctCoordinationRequesterProperties extends ServerProperties {
  
}
