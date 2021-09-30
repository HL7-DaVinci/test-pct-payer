package ca.uhn.fhir.jpa.starter.utils;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

import ca.uhn.fhir.context.ConfigurationException;

public class FileLoader {
    //public static final String FILE_NAME = "eob.json";
    /**
     *
     * @return json loaded from the topic-list.json containing the list of file url for bulk topic-list.
     */
    public static String loadTopics(String fileName) {
        try {
          InputStream in = FileLoader.class.getClassLoader().getResourceAsStream(fileName);
          String text = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
          return text;
        } catch (Exception e) {
            throw new ConfigurationException("Could not load json", e);
        }
    }
}
