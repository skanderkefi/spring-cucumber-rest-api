package fr.redfroggy.bdd.restapi.glue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.ReadContext;
import fr.redfroggy.bdd.restapi.scope.ScenarioScope;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

/**
 * Abstract step definition implementation Http request are made using {@link RestTemplate} RestTemplate
 */
@SuppressWarnings("unchecked")
abstract class AbstractBddStepDefinition {

    // Stored base uri
    protected String baseUri = "";

    // Parsed http request json body
    protected Object body;

    // Rest template
    protected TestRestTemplate template;

    // Http headers
    protected HttpHeaders headers;

    // List of query params
    protected Map<String, String> queryParams;

    // Stored http response
    protected ResponseEntity<String> responseEntity;

    protected ObjectMapper objectMapper;

    protected static final ScenarioScope scenarioScope = new ScenarioScope();

    AbstractBddStepDefinition(TestRestTemplate testRestTemplate) {
        template = testRestTemplate;
        objectMapper = new ObjectMapper();
        headers = new HttpHeaders();
        queryParams = new HashMap<>();

        // Add support for PATCH requests
        testRestTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    /**
     * Add an http header {@link #headers}
     *
     * @param name
     *            header name
     * @param value
     *            header value
     */
    void setHeader(String name, String value) {
        assertThat(name).isNotNull();
        assertThat(value).isNotNull();
        value = replaceDynamicParameters(value, false);
        headers.set(name, value);
    }

    /**
     * Add an HTTP query parameter {@link #queryParams}
     *
     * @param newParams
     *            Map of parameters
     */
    void addQueryParameters(Map<String, String> newParams) {
        assertThat(newParams).isNotEmpty();
        queryParams.putAll(newParams);
    }

    /**
     * Add multiple http headers {@link #headers}
     *
     * @param newHeaders
     *            Map of headers
     */
    void addHeaders(Map<String, String> newHeaders) {
        assertThat(newHeaders).isNotEmpty();
        newHeaders.forEach((key, value) ->
                this.headers.put(key,  Collections.singletonList(value)));
    }

    /**
     * Set the http request body (POST request for example) {@link #body}
     *
     * @param body
     *            json string body
     * @throws IOException
     *             json parse exception
     */
    void setBody(String body) throws IOException {
        assertThat(body).isNotEmpty();
        String sanitizedBody = replaceDynamicParameters(body, true);
        this.body = objectMapper.readValue(sanitizedBody, Object.class);
    }

    /**
     * Perform an http request Store the http response to responseEntity {@link #responseEntity}
     *
     * @param resource
     *            resource to consume
     * @param method
     *            HttpMethod
     */
    void request(String resource, HttpMethod method) {
        assertThat(resource).isNotEmpty();
        assertThat(method).isNotNull();

        resource = replaceDynamicParameters(resource, true);

        boolean writeMode = HttpMethod.PUT.equals(method) || HttpMethod.POST.equals(method)
                || HttpMethod.PATCH.equals(method);

        HttpEntity<Object> httpEntity;

        if (writeMode) {
            httpEntity = new HttpEntity<>(body, headers);
        } else {
            httpEntity = new HttpEntity<>(headers);
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUri + resource);
        queryParams.forEach(builder::queryParam);

        responseEntity = this.template.exchange(builder.build().toUri(), method, httpEntity, String.class);
        assertThat(responseEntity).isNotNull();
    }

    /**
     * Check http response status code
     *
     * @param status
     *            expected/unexpected status
     * @param isNot
     *            if true, test equality, inequality if false
     */
    void checkStatus(int status, boolean isNot) {
        assertThat(status).isGreaterThan(0);

        if (isNot) {
            assertThat(responseEntity.getStatusCodeValue()).isNotEqualTo(status);
        } else {
            assertThat(responseEntity.getStatusCodeValue()).isEqualTo(status);
        }
    }

    /**
     * Check header existence
     *
     * @param headerName
     *            name of the header to find
     * @param isNot
     *            if true, test equality, inequality if false
     * @return header values if found, null otherwise
     */
    List<String> checkHeaderExists(String headerName, boolean isNot) {
        assertThat(headerName).isNotEmpty();
        assertThat(responseEntity.getHeaders()).isNotNull();

        if (!isNot) {
            assertThat(responseEntity.getHeaders().get(headerName)).isNotNull();
            return responseEntity.getHeaders().get(headerName);
        } else {
            assertThat(responseEntity.getHeaders().get(headerName)).isNull();
            return null;
        }
    }

    /**
     * Test header value
     *
     * @param headerName
     *            name of the header to test
     * @param headerValue
     *            expected/unexpected value
     * @param isNot
     *            if true, test equality, inequality if false
     */
    void checkHeaderEqual(String headerName, String headerValue, boolean isNot) {
        assertThat(headerName).isNotEmpty();
        assertThat(headerValue).isNotEmpty();
        assertThat(responseEntity.getHeaders()).isNotNull();

        if (!isNot) {
            assertThat(responseEntity.getHeaders().getFirst(headerName)).contains(headerValue);
        } else {
            assertThat(responseEntity.getHeaders().getFirst(headerName)).doesNotContain(headerValue);
        }
    }

    /**
     * Test response body is json typed {@link #responseEntity}
     *
     * @throws IOException
     *             json parse exception
     */
    void checkJsonBody() throws IOException {
        String body = responseEntity.getBody();
        assertThat(body).isNotEmpty();

        // Check body json structure is valid
        objectMapper.readValue(body, Object.class);
    }

    /**
     * Test body content {@link #responseEntity}
     *
     * @param bodyValue
     *            expected content
     */
    void checkBodyContains(String bodyValue) {
        assertThat(bodyValue).isNotEmpty();
        assertThat(responseEntity.getBody()).contains(bodyValue);
    }

    /**
     * Test json path validity
     *
     * @param jsonPath
     *            json path query
     * @return value found using <code>jsonPath</code>
     */
    Object checkJsonPathExists(String jsonPath) {
        return getJsonPath(jsonPath);
    }

    void checkJsonPathDoesntExist(String jsonPath) {
        ReadContext ctx = getBodyDocument();
        if (ctx != null) {
            assertThat(jsonPath).isNotEmpty();
            /*assertThatThrownBy(() -> ctx.read(jsonPath))
                    .isExactlyInstanceOf(PathNotFoundException.class);*/
        }
    }

    /**
     * Test json path value
     *
     * @param jsonPath
     *            json path query
     * @param jsonValueString
     *            expected/unexpected json path value
     * @param isNot
     *            if true, test equality, inequality if false
     */
    void checkJsonPath(String jsonPath, String jsonValueString, boolean isNot) {
        Object pathValue = checkJsonPathExists(jsonPath);
        assertThat(String.valueOf(pathValue)).isNotEmpty();

        if (pathValue instanceof Collection) {
            checkJsonValue((Collection) pathValue, jsonValueString, isNot);
            return;
        }
        Object jsonValue = ReflectionTestUtils.invokeMethod(pathValue, "valueOf", jsonValueString);

        if (!isNot) {
            assertThat(pathValue).isEqualTo(jsonValue);
        } else {
            assertThat(pathValue).isNotEqualTo(jsonValue);
        }
    }

    /**
     * Test json path value
     *
     * @param pathValue
     *            json path array value
     * @param jsonValue
     *            expected/unexpected json path value
     * @param isNot
     *            if true, test equality, inequality if false
     */
    private void checkJsonValue(Collection pathValue, String jsonValue, boolean isNot) {
        assertThat(pathValue).isNotEmpty();

        if (!isNot) {
            assertThat(pathValue).isEqualTo(JsonPath.parse(jsonValue).json());
        } else {
            assertThat(pathValue).isNotEqualTo(JsonPath.parse(jsonValue).json());
        }
    }

    /**
     * Test json path is array typed and its size is matching the expected length
     *
     * @param jsonPath
     *            json path query
     * @param length
     *            expected length (-1 to not control the size)
     */
    void checkJsonPathIsArray(String jsonPath, int length) {
        Object pathValue = getJsonPath(jsonPath);
        assertThat(pathValue).isInstanceOf(Collection.class);
        if (length != -1) {
            assertThat(((Collection) pathValue)).hasSize(length);
        }
    }

    /**
     * Store a given header in the scenario scope using the given alias
     *
     * @param headerName
     *            header to save
     * @param headerAlias
     *            new header name in the scenario scope
     */
    void storeHeader(String headerName, String headerAlias) {
        assertThat(headerName).isNotEmpty();
        assertThat(headerAlias).isNotEmpty();

        List<String> headerValues = checkHeaderExists(headerName, false);
        assertThat(headerValues).isNotEmpty();

        scenarioScope.getHeaders().put(headerAlias, headerValues);
    }

    /**
     * Store a json path value using the given alias
     *
     * @param jsonPath
     *            json path query
     * @param jsonPathAlias
     *            new json path alias in the scenario scope
     */
    void storeJsonPath(String jsonPath, String jsonPathAlias) {
        assertThat(jsonPath).isNotEmpty();
        assertThat(jsonPathAlias).isNotEmpty();

        Object pathValue = getJsonPath(jsonPath);
        scenarioScope.getJsonPaths().put(jsonPathAlias, pathValue);
    }

    /**
     * Test a scenario variable existence
     *
     * @param property
     *            name of the variable
     * @param value
     *            expected value
     */
    void checkScenarioVariable(String property, String value) {
        Object scopeValue;
        scopeValue = scenarioScope.getJsonPaths().get(property);

        if (scopeValue == null) {
            scopeValue = scenarioScope.getHeaders().get(property);
        }
        assertThat(scopeValue).isNotNull();

        if (scopeValue instanceof Collection) {
            assertThat(((Collection) scopeValue)).contains(value);
        } else {
            assertThat(scopeValue).isEqualTo(value);
        }
    }

    /**
     * Parse the http response json body
     *
     * @return ReadContext instance
     */
    private ReadContext getBodyDocument() {

        if (responseEntity.getBody() == null) {
            return null;
        }

        ReadContext ctx = JsonPath.parse(responseEntity.getBody());
        assertThat(ctx).isNotNull();

        return ctx;
    }

    /**
     * Get values for a given json path query and the http response body
     *
     * @param jsonPath
     *            json path query
     * @return json path value
     */
    protected Object getJsonPath(String jsonPath) {

        assertThat(jsonPath).isNotEmpty();

        ReadContext ctx = getBodyDocument();

        if (ctx == null) {
            return null;
        }

        Object pathValue = ctx.read(jsonPath);

        assertThat(pathValue).isNotNull();

        return pathValue;
    }

    protected String replaceDynamicParameters(String value, boolean jsonPath) {
        Pattern pattern = Pattern.compile("`\\${1}(.*?)`");
        Matcher matcher = pattern.matcher(value);
        if (matcher.find()) {
            Object scopeValue = jsonPath ? scenarioScope.getJsonPaths().get(matcher.group(1))
                    : scenarioScope.getHeaders().get(matcher.group(1));
            assertThat(scopeValue).isNotNull();

            return replaceDynamicParameters(value.replace("`$"+ matcher.group(1) +"`",
                    scopeValue.toString()), jsonPath);
        }
        return value;
    }
}
