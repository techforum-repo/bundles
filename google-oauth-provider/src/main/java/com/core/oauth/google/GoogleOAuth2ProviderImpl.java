package com.core.oauth.google;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.scribe.builder.api.Api;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.auth.oauth.Provider;
import com.adobe.granite.auth.oauth.ProviderType;
import com.adobe.granite.security.user.UserPropertiesService;

@Component(name = "Adobe Granite OAuth Google Provider", service = Provider.class)
@Designate(ocd = GoogleOAuth2ProviderImpl.GoogleProviderConfiguration.class)
public class GoogleOAuth2ProviderImpl implements Provider {

	@ObjectClassDefinition(name = "Google Provider Configuration", description = "Google Provider Configuration")
	public @interface GoogleProviderConfiguration {

		@AttributeDefinition(name = "oauth.provider.id", description = "OAuth Provider ID")
		String providerId() default "google";

	}

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Reference
	private UserPropertiesService userPropertiesService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.STATIC)
	private ResourceResolverFactory resourceResolverFactory;

	private ResourceResolver serviceUserResolver;

	private final Api api = new GoogleOAuth2Api();

	private Session session;

	private String id;
	private String name;
	private static final String USER_ADMIN = "oauth-google-service";

	public static final String GOOGLE_DETAILS_URL = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json";

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public ProviderType getType() {
		return ProviderType.OAUTH2;
	}

	@Override
	public Api getApi() {
		return this.api;
	}

	@Override
	public String getDetailsURL() {
		return GOOGLE_DETAILS_URL;
	}

	@Override
	public String[] getExtendedDetailsURLs(String scope) {
		return new String[0];
	}

	@Override
	public String[] getExtendedDetailsURLs(String scope, String userId, Map<String, Object> props) {
		return new String[0];
	}

	public String mapProperty(String property) {
		if ("given_name".equals(property))
			return "profile/givenName";
		if ("family_name".equals(property))
			return "profile/familyName";

		return getPropertyPath(property);
	}

	@Override
	public String mapUserId(final String userId, final Map<String, Object> props) {
		final String userName = (String) props.get(getPropertyPath("id"));
		if (userName != null && userName.length() > 0) {
			return "go-" + userName;
		} else {
			return "go-" + userId;
		}
	}

	protected String getPropertyPath(final String property) {
		return "profile/" + property;
	}

	@Override
	public String getUserFolderPath(String userId, String clientId, Map<String, Object> props) {

		StringBuilder sb = new StringBuilder(getId());
		if (userId != null)
			sb.append("/").append(userId.substring(0, 4));
		return sb.toString();
	}

	@Override
	public Map<String, Object> mapProperties(String srcUrl, String clientId, Map<String, Object> existing,
			Map<String, String> newProperties) {
		Map<String, Object> mapped = new HashMap<>();
		mapped.putAll(existing);
		for (Map.Entry<String, String> prop : newProperties.entrySet()) {
			mapped.put(mapProperty(prop.getKey()), prop.getValue());
		}
		return mapped;
	}

	@Override
	public String getAccessTokenPropertyPath(String clientId) {
		return "profile/app-" + clientId;
	}

	@Override
	public User getCurrentUser(SlingHttpServletRequest request) {
		Authorizable authorizable = (Authorizable) request.adaptTo(Authorizable.class);
		if (authorizable != null && !authorizable.isGroup())
			return (User) authorizable;
		return null;
	}

	/**
	 * Called after a user is created by Granite
	 * 
	 * @param user
	 */
	@Override
	public void onUserCreate(final User user) {

		try {
			session.refresh(true);
			final Node userNode = session.getNode(userPropertiesService.getAuthorizablePath(user.getID()));
			final Node profNode = userNode.getNode("profile");
			
			if (user.hasProperty("profile/givenName")) {
				String firstName = user.getProperty("profile/givenName")[0].getString();
				profNode.setProperty("givenName", firstName);
			}

			if (user.hasProperty("profile/familyName")) {
				String lastName = user.getProperty("profile/familyName")[0].getString();
				profNode.setProperty("familyName", lastName);
			}

			session.save();			
		} catch (final RepositoryException e) {
			log.error("onUserCreate: failed to copy profile properties to cq profile", e);
		}
	}

	@Override
	public void onUserUpdate(User user) {
		onUserCreate(user);
	}

	@Override
	public OAuthRequest getProtectedDataRequest(String url) {
		return new OAuthRequest(Verb.GET, url);
	}

	@Override
	public Map<String, String> parseProfileDataResponse(Response response) throws IOException {
		String body = null;
		try {
			body = response.getBody();
			JSONObject json = new JSONObject(body);
			Map<String, String> newProps = new HashMap<>();
			for (Iterator<String> keys = json.keys(); keys.hasNext();) {
				String key = keys.next();
				newProps.put(key, json.optString(key));
			}
			return newProps;
		} catch (JSONException je) {
			this.log.debug("problem parsing JSON; response body was: {}", body);
			throw new IOException(je.toString());
		} catch (Exception e) {
			this.log.error("Exception while parsing profile data");
			throw new IOException(e.toString());
		}
	}

	public String getUserIdProperty() {
		return "id";
	}

	public String getOAuthIdPropertyPath(String clientId) {
		return "oauth/oauthid-" + clientId;
	}

	public String getValidateTokenUrl(String clientId, String token) {
		this.log.info("This provider doesn't support the validation of a token");
		return null;
	}

	public boolean isValidToken(String responseBody, String clientId, String tokenType) {
		this.log.info("This provider doesn't support the validation of a token");
		return false;
	}

	public String getUserIdFromValidateTokenResponseBody(String responseBody) {
		this.log.info("This provider doesn't support the validation of a token");
		return null;
	}

	public String getErrorDescriptionFromValidateTokenResponseBody(String responseBody) {
		this.log.info("This provider doesn't support the validation of a token");
		return null;
	}

	@Activate
	protected void activate(final GoogleProviderConfiguration config) throws Exception {
		name = getClass().getSimpleName();
		id = config.providerId();

		Map<String, Object> serviceParams = new HashMap<String, Object>();
		serviceParams.put(ResourceResolverFactory.SUBSERVICE, USER_ADMIN);
		serviceUserResolver = this.resourceResolverFactory.getServiceResourceResolver(serviceParams);
		session = serviceUserResolver.adaptTo(Session.class);

	}

	@Deactivate
	protected void deactivate(final ComponentContext componentContext) throws Exception {
		log.debug("deactivating provider id {}", id);
		if (session != null && session.isLive()) {
			try {
				session.logout();
			} catch (final Exception e) {
				// ignore
			}
			session = null;
		}
		if (serviceUserResolver != null) {
			serviceUserResolver.close();
		}
	}

}
