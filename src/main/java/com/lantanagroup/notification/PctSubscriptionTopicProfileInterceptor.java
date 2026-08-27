package com.lantanagroup.notification;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Subscription;

import java.util.Arrays;
import java.util.List;

/**
 * HAPI's SubscriptionValidatingInterceptor (ca.uhn.fhir.jpa.subscription.match.registry.SubscriptionCanonicalizer
 * #canonicalizeR4) only treats a Subscription as a topic-based (Subscriptions R5 Backport) subscription when
 * Subscription.meta.profile contains the backport-subscription base profile URL exactly. If it's absent, HAPI
 * instead validates Subscription.criteria as a legacy resource-type search expression and rejects a SubscriptionTopic
 * canonical URL with HAPI-0013.
 * <p>
 * The PCT IG's subscription profiles derive from that base profile but, per the IG's own published examples,
 * don't redeclare it in meta.profile - only the PCT-specific profile is listed. This interceptor adds the missing
 * base profile whenever a known PCT subscription profile is present, running before HAPI's own validator
 * (order -100 vs. its default of 0) so the resource is already correctly marked by the time it validates.
 */
@Interceptor(order = -100)
public class PctSubscriptionTopicProfileInterceptor {

  private static final String BACKPORT_SUBSCRIPTION_PROFILE =
      "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-subscription";

  private static final List<String> PCT_TOPIC_SUBSCRIPTION_PROFILES = Arrays.asList(
      "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-available-author-subscription",
      "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-aeob-available-subject-subscription",
      "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-available-author-subscription",
      "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-available-subject-subscription",
      "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-task-update-subscription"
  );

  @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
  public void resourcePreCreate(IBaseResource theResource) {
    addBackportProfileIfNeeded(theResource);
  }

  @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
  public void resourcePreUpdate(IBaseResource theResource) {
    addBackportProfileIfNeeded(theResource);
  }

  private void addBackportProfileIfNeeded(IBaseResource theResource) {
    if (!(theResource instanceof Subscription)) {
      return;
    }

    List<CanonicalType> profiles = ((Subscription) theResource).getMeta().getProfile();
    boolean hasPctTopicProfile = profiles.stream()
        .map(CanonicalType::getValueAsString)
        .anyMatch(PCT_TOPIC_SUBSCRIPTION_PROFILES::contains);
    boolean hasBackportProfile = profiles.stream()
        .map(CanonicalType::getValueAsString)
        .anyMatch(BACKPORT_SUBSCRIPTION_PROFILE::equals);

    if (hasPctTopicProfile && !hasBackportProfile) {
      ((Subscription) theResource).getMeta().addProfile(BACKPORT_SUBSCRIPTION_PROFILE);
    }
  }
}
