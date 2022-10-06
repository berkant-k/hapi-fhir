package ca.uhn.fhir.jpa.interceptor;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2022 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DeleteConflictList;
import ca.uhn.fhir.jpa.dao.tx.HapiTransactionService;
import ca.uhn.fhir.jpa.delete.DeleteConflictOutcome;
import ca.uhn.fhir.jpa.delete.SafeDeleter;
import ca.uhn.fhir.rest.api.DeleteCascadeModeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.server.RestfulServerUtils;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static ca.uhn.fhir.jpa.delete.DeleteConflictService.MAX_RETRY_ATTEMPTS;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Interceptor that allows for cascading deletes (deletes that resolve constraint issues).
 * <p>
 * For example, if <code>DiagnosticReport/A</code> has a reference to <code>Observation/B</code>
 * it is not normally possible to delete <code>Observation/B</code> without first deleting
 * <code>DiagnosticReport/A</code>. With this interceptor in place, it is.
 * </p>
 * <p>
 * When using this interceptor, client requests must include the parameter
 * <code>_cascade=delete</code> on the DELETE URL in order to activate
 * cascading delete, or include the request header <code>X-Cascade-Delete: delete</code>
 * </p>
 */
@Interceptor
public class CascadingDeleteInterceptor {

	/*
	 * We keep the orders for the various handlers of {@link Pointcut#STORAGE_PRESTORAGE_DELETE_CONFLICTS} in one place
	 * so it's easy to compare them
	 */
	public static final int OVERRIDE_PATH_BASED_REF_INTEGRITY_INTERCEPTOR_ORDER = 0;
	public static final int CASCADING_DELETE_INTERCEPTOR_ORDER = 1;

	private static final Logger ourLog = LoggerFactory.getLogger(CascadingDeleteInterceptor.class);
	private static final String CASCADED_DELETES_KEY = CascadingDeleteInterceptor.class.getName() + "_CASCADED_DELETES_KEY";
	private static final String CASCADED_DELETES_FAILED_KEY = CascadingDeleteInterceptor.class.getName() + "_CASCADED_DELETES_FAILED_KEY";

	private final DaoRegistry myDaoRegistry;
	private final IInterceptorBroadcaster myInterceptorBroadcaster;
	private final FhirContext myFhirContext;
	// TODO: LUKE:  get rid of this from list of fields, constructor and constructor calls if it's no longer needed in the final solution
	private final HapiTransactionService myHapiTransactionService;
	private final RetryTemplate myRetryTemplate;

	/**
	 * Constructor
	 *
	 * @param theDaoRegistry The DAO registry (must not be null)
	 */
	public CascadingDeleteInterceptor(@Nonnull FhirContext theFhirContext, @Nonnull DaoRegistry theDaoRegistry, @Nonnull IInterceptorBroadcaster theInterceptorBroadcaster, @Nonnull HapiTransactionService theHapiTransactionService, @Nonnull RetryTemplate theRetryTemplate) {
		Validate.notNull(theDaoRegistry, "theDaoRegistry must not be null");
		Validate.notNull(theInterceptorBroadcaster, "theInterceptorBroadcaster must not be null");
		Validate.notNull(theFhirContext, "theFhirContext must not be null");
//		Validate.notNull(theHapiTransactionService, "theHapiTransactionService must not be null");
		Validate.notNull(theRetryTemplate, "theRetryTemplate must not be null");

		myDaoRegistry = theDaoRegistry;
		myInterceptorBroadcaster = theInterceptorBroadcaster;
		myFhirContext = theFhirContext;
		// TODO: LUKE:  get rid of this from list of fields, constructor and constructor calls if it's no longer needed in the final solution
		myHapiTransactionService = theHapiTransactionService;
		myRetryTemplate = theRetryTemplate;
	}

	@Hook(value = Pointcut.STORAGE_PRESTORAGE_DELETE_CONFLICTS, order = CASCADING_DELETE_INTERCEPTOR_ORDER)
	public DeleteConflictOutcome handleDeleteConflicts(DeleteConflictList theConflictList, RequestDetails theRequest, TransactionDetails theTransactionDetails) {
		ourLog.debug("Have delete conflicts: {}", theConflictList);

		if (shouldCascade(theRequest) == DeleteCascadeModeEnum.NONE) {

			// Add a message to the response
			String message = myFhirContext.getLocalizer().getMessage(CascadingDeleteInterceptor.class, "noParam");
			ourLog.trace(message);

			if (theRequest != null) {
				theRequest.getUserData().put(CASCADED_DELETES_FAILED_KEY, message);
			}

			return null;
		}

		SafeDeleter deleter = new SafeDeleter(myDaoRegistry, myInterceptorBroadcaster, myHapiTransactionService.getTransactionManager(), getRetryTemplate());
		deleter.delete(theRequest, theConflictList, theTransactionDetails);

		return new DeleteConflictOutcome().setShouldRetryCount(MAX_RETRY_ATTEMPTS);
	}

	// TODO LUKE move this
	public static List<String> getCascadedDeletesList(RequestDetails theRequest, boolean theCreate) {
		List<String> retVal = (List<String>) theRequest.getUserData().get(CASCADED_DELETES_KEY);
		if (retVal == null && theCreate) {
			retVal = new ArrayList<>();
			theRequest.getUserData().put(CASCADED_DELETES_KEY, retVal);
		}
		return retVal;
	}

	@Hook(Pointcut.SERVER_OUTGOING_FAILURE_OPERATIONOUTCOME)
	public void outgoingFailureOperationOutcome(RequestDetails theRequestDetails, IBaseOperationOutcome theResponse) {
		if (theRequestDetails != null) {

			String failedDeleteMessage = (String) theRequestDetails.getUserData().get(CASCADED_DELETES_FAILED_KEY);
			if (isNotBlank(failedDeleteMessage)) {
				FhirContext ctx = theRequestDetails.getFhirContext();
				String severity = OperationOutcome.IssueSeverity.INFORMATION.toCode();
				String code = OperationOutcome.IssueType.INFORMATIONAL.toCode();
				String details = failedDeleteMessage;
				OperationOutcomeUtil.addIssue(ctx, theResponse, severity, details, null, code);
			}

		}
	}


	@Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
	public void outgoingResponse(RequestDetails theRequestDetails, ResponseDetails theResponseDetails, IBaseResource theResponse) {
		if (theRequestDetails != null) {

			// Successful delete list
			List<String> deleteList = getCascadedDeletesList(theRequestDetails, false);
			if (deleteList != null) {
				if (theResponseDetails.getResponseCode() == 200) {
					if (theResponse instanceof IBaseOperationOutcome) {
						FhirContext ctx = theRequestDetails.getFhirContext();
						IBaseOperationOutcome oo = (IBaseOperationOutcome) theResponse;
						String severity = OperationOutcome.IssueSeverity.INFORMATION.toCode();
						String code = OperationOutcome.IssueType.INFORMATIONAL.toCode();
						String details = ctx.getLocalizer().getMessage(CascadingDeleteInterceptor.class, "successMsg", deleteList.size(), deleteList);
						OperationOutcomeUtil.addIssue(ctx, oo, severity, details, null, code);
					}
				}
			}

		}
	}


	/**
	 * Subclasses may override
	 *
	 * @param theRequest The REST request (may be null)
	 * @return Returns true if cascading delete should be allowed
	 */
	@Nonnull
	protected DeleteCascadeModeEnum shouldCascade(@Nullable RequestDetails theRequest) {
		return RestfulServerUtils.extractDeleteCascadeParameter(theRequest);
	}

	// TODO:  set this up in a Config class:  somewhere
	private static RetryTemplate getRetryTemplate() {
		final long BACKOFF_PERIOD = 100L;
		final int MAX_ATTEMPTS = 4;

		RetryTemplate retryTemplate = new RetryTemplate();

		FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
		fixedBackOffPolicy.setBackOffPeriod(BACKOFF_PERIOD);
		retryTemplate.setBackOffPolicy(fixedBackOffPolicy);


		SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(MAX_ATTEMPTS, Collections.singletonMap(ResourceVersionConflictException.class, true));
		retryTemplate.setRetryPolicy(retryPolicy);

		return retryTemplate;
	}

}
