package edu.gatech.chai.hl7.v2.parser.fhir;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.hl7v2.model.GenericMessage;
import ca.uhn.hl7v2.model.v251.message.ORU_R01;
import ca.uhn.hl7v2.parser.PipeParser;
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

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;

public class Hl7v2FhirOverHttpApp implements RequestHandler<SQSEvent, Void>{
	/**
	 * 
	 */
	private final long serialVersionUID = 1L;
	private IHL7v2FHIRParser hl7FhirParser;
	private FhirContext ctx;
	@Override
    public Void handleRequest(SQSEvent event, Context context){
    	setParserAndContextVersion();
        for(SQSMessage msg : event.getRecords()){
			//need to get msg into HL7 Message format
        	//https://saravanansubramanian.com/hl72xhapiparsemessage/
			PipeParser ourPipeParser = new PipeParser();
			Message hl7Message = null;
			String decoded= new String(Base64.getDecoder().decode(msg.getBody()));
			decoded=decoded.replace("\r\n","\r");
			decoded=decoded.replace("\n","\r");
			try {
				hl7Message = ourPipeParser.parse(decoded);
			} catch (HL7Exception e) {
				System.out.println("hit HL7Exception1");
				e.printStackTrace();
			}

			try {
				processMessage(hl7Message,null);
				System.out.println("made it out of processMessage successfully");
			} catch (ReceivingApplicationException e) {
				System.out.println("hit ReceivingApplicationException");
				e.printStackTrace();
			} catch (HL7Exception e) {
				System.out.println("hit HL7Exception2");
				e.printStackTrace();
			}
		}
        System.out.println("end of handleRequest");
        return null;
    }
	/**
	 * Initialise the servlet
	 */
	// @Override
	// public void init(ServletConfig theConfig) throws ServletException {

		
	// 	 * Servlet should be initialized with an instance of ReceivingApplication, which
	// 	 * handles incoming messages
		 
	// 	setApplication(new MyApplication());
	// 	setParserAndContextVersion();
	// }

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
		ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ctx.getRestfulClientFactory().setConnectTimeout(600 * 1000);
		ctx.getRestfulClientFactory().setSocketTimeout(600 * 1000);
	}

		private void sendFhir(IBaseBundle bundle, String requestUrl, IGenericClient client)
				throws ReceivingApplicationException, HL7Exception {
			if (requestUrl == null || requestUrl.isEmpty()) {
				requestUrl = "http://localhost:8080/fhir";
			}

			try {
//				Bundle response = client.operation().processMessage().setMessageBundle(bundle).synchronous(Bundle.class)
//						.execute();
				Bundle response = client.transaction().withBundle((Bundle)bundle).execute();
				System.out.println("the response is: \n"+response.toString());
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
		public Message processMessage(Message theMessage, Map<String, Object> theMetadata) throws HL7Exception, ReceivingApplicationException {
			System.out.println("Received message:\n" + theMessage.encode());
			ORU_R01 oruR01Message = (ca.uhn.hl7v2.model.v251.message.ORU_R01) theMessage;
			int numberOfResponses = oruR01Message.getPATIENT_RESULTReps();
			System.out.println("number of responses is: "+numberOfResponses);
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
			System.out.println("bundles are: \n"+bundles.toString());
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
				System.out.println("would post data here");
				if (requestUrl != null) {
					System.out.println("transaction bundle: "+bundle.toString());
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
