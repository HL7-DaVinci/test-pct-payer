package com.lantanagroup.servers.davincipctcoordinationplatform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.lantanagroup.common.ServerProperties;

@Configuration
@ConfigurationProperties(prefix = "davincipctcoordinationplatform")
public class DavinciPctCoordinationPlatformProperties extends ServerProperties {
  
}
