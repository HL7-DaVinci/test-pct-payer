package ca.uhn.fhir.jpa.starter.utils;
import java.util.*;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for handling http requests
 */
public class RequestHandler {
  private final Logger myLogger = LoggerFactory.getLogger(RequestHandler.class.getName());

    private final String USER_AGENT = "Mozilla/5.0";
    public RequestHandler() {

    }
    /**
     * Sends a get message to the endpoint with parameters
     * @param  endpoint  the endpoint
     * @param  params    the parameters to add to the endpoint
     * @return           the response to the get message
     * @throws Exception an error based on sending the message or getting a response
     */
    public String sendGet(String endpoint, String params) throws Exception {
        String url = endpoint + params;
        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();

        // optional default is GET
        con.setRequestMethod("GET");

        int responseCode = con.getResponseCode();
        myLogger.info("\nSending 'GET' request to URL : " + url + params);
        myLogger.info("Response Code : " + responseCode);

        BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();

        while ((inputLine = in.readLine()) != null) {
          response.append(inputLine);
        }
        in.close();
        myLogger.info("Finished Get");
        return response.toString();

  }
  /**
   * Posts data to the endpoint
   * @param  endpoint  the endpoint
   * @param  data      the data to post to the endpoint
   * @return           the response to the get message
   * @throws Exception an error based on sending the message or getting a response
   */
  public String sendPost(String endpoint, String data) throws Exception {
        String url = endpoint;
        // Do not use this in production
        TrustManager[] trustAllCerts = new TrustManager[] {
           new X509TrustManager() {
              public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
              }
              public void checkClientTrusted(X509Certificate[] certs, String authType) {  }
              public void checkServerTrusted(X509Certificate[] certs, String authType) {  }
           }
        };

        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

        // Create all-trusting host name verifier
        HostnameVerifier allHostsValid = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
              return true;
            }
        };
        // Install the all-trusting host verifier
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

        myLogger.info("Sending Post to: " + url);
        URL obj = new URL(url);

        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; utf-8");
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            byte[] input = data.getBytes("utf-8");
            os.write(input, 0, input.length);
        } catch (Exception e) {
            myLogger.info("Exception: " + e);            
        }
        int responseCode = con.getResponseCode();
        myLogger.info("Response Code: " + responseCode);            

        BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();

        while ((inputLine = in.readLine()) != null) {
          response.append(inputLine);
        }
        in.close();
        myLogger.info("Response:" + response.toString());            
        return response.toString();
    }
    /**
     * Sends a put request with data to the endpoint
     * @param  endpoint  the endpoint
     * @param  data      the data to post to the endpoint
     * @return           the response to the get message
     * @throws Exception an error based on sending the message or getting a response
     */
    public String sendPut(String endpoint, String data) throws Exception {
          String url = endpoint;
          // Do not use this in production
          TrustManager[] trustAllCerts = new TrustManager[] {
             new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                  return null;
                }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {  }
                public void checkServerTrusted(X509Certificate[] certs, String authType) {  }
             }
          };

          SSLContext sc = SSLContext.getInstance("SSL");
          sc.init(null, trustAllCerts, new java.security.SecureRandom());
          HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

          // Create all-trusting host name verifier
          HostnameVerifier allHostsValid = new HostnameVerifier() {
              public boolean verify(String hostname, SSLSession session) {
                return true;
              }
          };
          // Install the all-trusting host verifier
          HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

          myLogger.info("Sending Put to: " + url);            
          URL obj = new URL(url);

          HttpURLConnection con = (HttpURLConnection) obj.openConnection();
          con.setRequestMethod("PUT");
          con.setRequestProperty("Content-Type", "application/json; utf-8");
          con.setDoOutput(true);

          try (OutputStream os = con.getOutputStream()) {
              byte[] input = data.getBytes("utf-8");
              os.write(input, 0, input.length);
          } catch (Exception e) {
              myLogger.info("Exception: " + e);                          
          }
          int responseCode = con.getResponseCode();
          myLogger.info("Response Code: " + responseCode);                          

          BufferedReader in = new BufferedReader(
                  new InputStreamReader(con.getInputStream()));
          String inputLine;
          StringBuffer response = new StringBuffer();

          while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
          }
          in.close();
          myLogger.info("Response:" + response.toString());                                    
          return response.toString();
      }

}
