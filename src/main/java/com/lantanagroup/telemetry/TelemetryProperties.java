package com.lantanagroup.telemetry;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Telemetry settings under the "telemetry" prefix in application.yaml
 */
@Configuration
@ConfigurationProperties(prefix = "telemetry")
public class TelemetryProperties {

	/**
	 * Search query strings can carry patient identifiers, so they stay out of server spans and the
	 * request log unless a deployment sets this to true.
	 */
	private boolean captureQueryStrings = false;

	/**
	 * The server serves every registered resource type. This list limits which types get their own value
	 * on the request metric, and every other registered type counts as "other". When the list is empty,
	 * every registered type is recorded as is.
	 */
	private List<String> resourceTypes = new ArrayList<>();

	public boolean isCaptureQueryStrings() {
		return captureQueryStrings;
	}

	public void setCaptureQueryStrings(boolean captureQueryStrings) {
		this.captureQueryStrings = captureQueryStrings;
	}

	public List<String> getResourceTypes() {
		return resourceTypes;
	}

	public void setResourceTypes(List<String> resourceTypes) {
		this.resourceTypes = resourceTypes;
	}
}
