package ca.uhn.fhir.jpa.starter.authorization.authproxy;


import java.util.UUID;

public class AuthResponse {
  private String launch_id;

  AuthResponse() {
    this.launch_id = UUID.randomUUID().toString();
  }

  public String getlaunch_id() {
    return launch_id;
  }



}
