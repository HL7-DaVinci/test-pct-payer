package com.lantanagroup.servers.davincipctgfecontributor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.lantanagroup.common.ServerProperties;

@Configuration
@ConfigurationProperties(prefix = "davincipctgfecontributor")
public class DavinciPctGfeContributorProperties extends ServerProperties {
  
}
