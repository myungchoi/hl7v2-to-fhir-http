package edu.gatech.chai.hl7.v2.parser.fhir;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.json.JSONObject;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.hoh.hapi.server.HohServlet;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;

public class Hl7v2FhirOverHttpApp extends HohServlet {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private IHL7v2FHIRParser hl7FhirParser;
	private FhirContext ctx;

	/**
	 * Initialise the servlet
	 */
	@Override
	public void init(ServletConfig theConfig) throws ServletException {

		/*
		 * Servlet should be initialized with an instance of ReceivingApplication, which
		 * handles incoming messages
		 */
		setApplication(new MyApplication());
		setParserAndContextVersion();
	}

	public void setParserAndContextVersion() {
		String HL7Version = System.getenv("HL7_MESSAGE_VERSION");
		String FHIRVersion = System.getenv("FHIR_VERSION");
		if ("v2.5.1".equals(HL7Version)) {
			if ("R4".equals(FHIRVersion)) {
				ctx = FhirContext.forR4();
				hl7FhirParser = new HL7v251FhirR4Parser();
			}
		} else if ("v2.3".equals(HL7Version)) {
			if ("R4".equals(FHIRVersion)) {
				ctx = FhirContext.forR4();
				hl7FhirParser = new HL7v23FhirR4Parser();
			}
			if ("STU3".equals(FHIRVersion)) {
				ctx = FhirContext.forDstu3();
				hl7FhirParser = new HL7v23FhirStu3Parser();
			}
		} else {
			// default
			ctx = FhirContext.forR4();
			hl7FhirParser = new HL7v23FhirR4Parser();
		}

		ctx.getRestfulClientFactory().setConnectTimeout(600 * 1000);
		ctx.getRestfulClientFactory().setSocketTimeout(600 * 1000);
	}

	/**
	 * The application does the actual processing
	 */
	private class MyApplication implements ReceivingApplication<Message> {

		private void sendFhir(IBaseBundle bundle, String requestUrl, IGenericClient client)
				throws ReceivingApplicationException, HL7Exception {
			if (requestUrl == null || requestUrl.isEmpty()) {
				requestUrl = "http://localhost:8080/fhir";
			}

			try {
				Bundle response = client.operation().processMessage().setMessageBundle(bundle).synchronous(Bundle.class)
						.execute();
				if (response == null || response.isEmpty()) {
					throw new ReceivingApplicationException("Failed to send to FHIR message");
				}

			} catch (Exception e) {
				throw new ReceivingApplicationException(e);
			}
		}

		private Bundle makeTransactionFromMessage (Bundle bundle) {
			// Write transaction
			Bundle transactionBundle = (Bundle) bundle;
			transactionBundle.setType(BundleType.TRANSACTION);
			List<BundleEntryComponent> entries = transactionBundle.getEntry();
			for (BundleEntryComponent entry : entries) {
				Resource resource = entry.getResource();
				ResourceType resourceType = resource.getResourceType();
				String resourceTypeString = resourceType.name();
				BundleEntryRequestComponent entryRequest = entry.getRequest();
				entryRequest.setMethod(HTTPVerb.POST);
				entryRequest.setUrl(resourceTypeString);
			}
			return transactionBundle;
		}
		
		public void saveJsonToFile(IBaseBundle bundle, String filename) {
			try {
				if (filename != null && !filename.isEmpty()) {
					String fhirJson = ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
					BufferedWriter writer = new BufferedWriter(new FileWriter(filename));
					writer.write(fhirJson);
					writer.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}

//			System.out.println(fhirJsonObject.toString());
//			also need it to save to a file
		}

		/**
		 * processMessage is fired each time a new message arrives.
		 * 
		 * @param theMessage  The message which was received
		 * @param theMetadata A map containing additional information about the message,
		 *                    where it came from, etc.
		 */
		@Override
		public Message processMessage(Message theMessage, Map<String, Object> theMetadata)
				throws ReceivingApplicationException, HL7Exception {
			System.out.println("Received message:\n" + theMessage.encode());
			List<IBaseBundle> bundles = hl7FhirParser.executeParser(theMessage);
			String saveToFile = System.getenv("SAVE_TO_FILE");
			String requestUrl = System.getenv("FHIR_PROCESS_MESSAGE_URL");
			IGenericClient client = null;
			if (requestUrl != null) {
				client = ctx.newRestfulGenericClient(requestUrl);
				String authBasic = System.getenv("AUTH_BASIC");
				String authBearer = System.getenv("AUTH_BEARER");
				if (authBasic != null && !authBasic.isEmpty()) {
					String[] auth = authBasic.split(":");
					if (auth.length == 2) {
						client.registerInterceptor(new BasicAuthInterceptor(auth[0], auth[1]));
					}
				} else if (authBearer != null && !authBearer.isEmpty()) {
					client.registerInterceptor(new BearerTokenAuthInterceptor(authBearer));
				} else {
					client.registerInterceptor(new BasicAuthInterceptor("client", "secret"));
				}
			}
			for (IBaseBundle bundle : bundles) {

				String fhirJson = ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
				JSONObject fhirJsonObject = new JSONObject(fhirJson);

				System.out.println(fhirJsonObject.toString());

				String filePath = System.getenv("FILEPATH_WRITE");
				String fileUnique = String.valueOf(System.currentTimeMillis());
				if ("YES".equals(saveToFile)) {					
					String filename = filePath + "/" + fileUnique + "_message.txt";
					saveJsonToFile(bundle, filename);
				}

				// change it to transaction bundle.
				bundle = makeTransactionFromMessage((Bundle)bundle);
				if (requestUrl != null) {
					// .. process the message ..
					sendFhir(bundle, requestUrl, client);
				}
				
				if ("YES".equals(saveToFile)) {
					String filename = filePath + "/" + fileUnique + "_transaction.txt";
					saveJsonToFile(bundle, filename);
				}
			}

			/*
			 * Now reply to the message
			 */
			Message response;
			try {
				response = theMessage.generateACK();
			} catch (IOException e) {
				throw new ReceivingApplicationException(e);
			}

			/*
			 * If something goes horribly wrong, you can throw an exception and an HTTP 500
			 * error will be generated. However, it is preferable to return a normal HL7 ACK
			 * message with an "AE" response code to note an error.
			 */
			boolean somethingFailed = false;
			if (somethingFailed) {
				throw new ReceivingApplicationException("");
			}

			/*
			 * It is better to return an HL7 message with an AE response code. This will
			 * still be returned by the transport with an HTTP 500 status code, but an HL7
			 * message will still be propagated up.
			 */
//			if (somethingFailed) {
//				try {
//					response = theMessage.generateACK("AE", new HL7Exception("There was a problem!!"));
//				} catch (IOException e) {
//					throw new ReceivingApplicationException(e);
//				}
//			}

			return response;
		}

		/**
		 * {@inheritDoc}
		 */
		public boolean canProcess(Message theMessage) {
			return true;
		}

	}
}
