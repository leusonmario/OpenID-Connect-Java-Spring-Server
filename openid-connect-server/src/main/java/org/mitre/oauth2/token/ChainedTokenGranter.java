/*******************************************************************************
 * Copyright 2013 The MITRE Corporation
 *   and the MIT Kerberos and Internet Trust Consortium
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
/**
 * 
 */
package org.mitre.oauth2.token;

import java.util.HashSet;
import java.util.Set;

import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.model.OAuth2AccessTokenEntity;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mitre.oauth2.service.OAuth2TokenEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.common.exceptions.InvalidScopeException;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.AuthorizationRequest;
import org.springframework.security.oauth2.provider.DefaultAuthorizationRequest;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.AbstractTokenGranter;
import org.springframework.stereotype.Component;

import com.google.common.collect.Sets;

/**
 * @author jricher
 *
 */
@Component("chainedTokenGranter")
public class ChainedTokenGranter extends AbstractTokenGranter {

	private static final String grantType = "urn:ietf:params:oauth:grant_type:redelegate";

	// keep down-cast versions so we can get to the right queries
	private OAuth2TokenEntityService tokenServices;

	/**
	 * @param tokenServices
	 * @param clientDetailsService
	 * @param grantType
	 */
	@Autowired
	public ChainedTokenGranter(OAuth2TokenEntityService tokenServices, ClientDetailsEntityService clientDetailsService) {
		super(tokenServices, clientDetailsService, grantType);
		this.tokenServices = tokenServices;
	}

	/* (non-Javadoc)
	 * @see org.springframework.security.oauth2.provider.token.AbstractTokenGranter#getOAuth2Authentication(org.springframework.security.oauth2.provider.AuthorizationRequest)
	 */
	@Override
	protected OAuth2Authentication getOAuth2Authentication(AuthorizationRequest authorizationRequest) throws AuthenticationException, InvalidTokenException {
		// read and load up the existing token
		String incomingTokenValue = authorizationRequest.getAuthorizationParameters().get("token");
		OAuth2AccessTokenEntity incomingToken = tokenServices.readAccessToken(incomingTokenValue);

		// check for scoping in the request, can't up-scope with a chained request
		Set<String> approvedScopes = incomingToken.getScope();
		Set<String> requestedScopes = authorizationRequest.getScope();

		if (requestedScopes == null) {
			requestedScopes = new HashSet<String>();
		}

		// do a check on the requested scopes -- if they exactly match the client scopes, they were probably shadowed by the token granter
		// FIXME: bug in SECOAUTH functionality
		ClientDetailsEntity client = incomingToken.getClient();
		if (client.getScope().equals(requestedScopes)) {
			requestedScopes = new HashSet<String>();
		}

		// if our scopes are a valid subset of what's allowed, we can continue
		if (approvedScopes.containsAll(requestedScopes)) {

			// build an appropriate auth request to hand to the token services layer
			DefaultAuthorizationRequest outgoingAuthRequest = new DefaultAuthorizationRequest(authorizationRequest);
			outgoingAuthRequest.setApproved(true);
			if (requestedScopes.isEmpty()) {
				// if there are no scopes, inherit the original scopes from the token
				outgoingAuthRequest.setScope(approvedScopes);
			} else {
				// if scopes were asked for, give only the subset of scopes requested
				// this allows safe downscoping
				outgoingAuthRequest.setScope(Sets.intersection(requestedScopes, approvedScopes));
			}

			// NOTE: don't revoke the existing access token

			// create a new access token
			OAuth2Authentication authentication = new OAuth2Authentication(outgoingAuthRequest, incomingToken.getAuthenticationHolder().getAuthentication().getUserAuthentication());

			return authentication;

		} else {
			throw new InvalidScopeException("Invalid scope requested in chained request", approvedScopes);
		}

	}

}
