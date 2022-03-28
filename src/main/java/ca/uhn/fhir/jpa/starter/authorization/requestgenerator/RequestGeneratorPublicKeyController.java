package ca.uhn.fhir.jpa.starter.authorization.requestgenerator;

import ca.uhn.fhir.jpa.starter.authorization.requestgenerator.database.Key;
import ca.uhn.fhir.jpa.starter.authorization.requestgenerator.database.KeyDAOImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

@RestController
public class RequestGeneratorPublicKeyController {
  static final Logger logger = LoggerFactory.getLogger(RequestGeneratorPublicKeyController.class);

  @Autowired
  private KeyDAOImpl keyJDBC;

  @GetMapping("/public/{id}")
  public Key getKey(@PathVariable String id) throws SQLException {
    logger.info("EHRKeyController: Getting key with id: " + id);
    return keyJDBC.getKey(id);
  }

  @PostMapping("/public")
  public ResponseEntity<Object> postKey(@RequestBody Key jsonData) {
    keyJDBC.createKey(jsonData);
    return ResponseEntity.noContent().build();
  }
}
