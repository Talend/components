// ============================================================================
//
// Copyright (C) 2006-2016 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.components.azure.dlsgen2.blob.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.PublicAccessType;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;
import org.talend.components.api.container.RuntimeContainer;
import org.talend.components.api.exception.ComponentException;
import org.talend.components.azure.dlsgen2.RuntimeContainerMock;
import org.talend.components.azure.dlsgen2.blob.AzureDlsGen2BlobService;
import org.talend.components.azure.dlsgen2.blob.tazurestoragecontainercreate.TAzureDlsGen2ContainerCreateProperties;
import org.talend.components.azure.dlsgen2.blob.tazurestoragecontainercreate.TAzureDlsGen2ContainerCreateProperties.AccessControl;
import org.talend.components.azure.dlsgen2.tazurestorageconnection.TAzureDlsGen2ConnectionProperties;
import org.talend.components.azure.dlsgen2.tazurestorageconnection.TAzureDlsGen2ConnectionProperties.Protocol;
import org.talend.daikon.i18n.GlobalI18N;
import org.talend.daikon.i18n.I18nMessages;
import org.talend.daikon.properties.ValidationResult;

public class AzureStorageContainerCreateRuntimeTest {

    public static final String PROP_ = "PROP_";

    private static final I18nMessages messages = GlobalI18N.getI18nMessageProvider()
            .getI18nMessages(AzureDlsGen2ContainerCreateRuntime.class);

    private RuntimeContainer runtimeContainer;

    private TAzureDlsGen2ContainerCreateProperties properties;

    private AzureDlsGen2ContainerCreateRuntime containerCreate;

    @Mock
    private AzureDlsGen2BlobService blobService;

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Before
    public void setup() {
        properties = new TAzureDlsGen2ContainerCreateProperties(PROP_ + "CreateContainer");
        properties.setupProperties();
        // valid connection
        properties.connection = new TAzureDlsGen2ConnectionProperties(PROP_ + "Connection");
        properties.connection.protocol.setValue(Protocol.HTTP);
        properties.connection.accountName.setValue("fakeAccountName");
        properties.connection.accountKey.setValue("fakeAccountKey=ANBHFYRJJFHRIKKJFU");
        properties.accessControl.setValue(AccessControl.Public);

        runtimeContainer = new RuntimeContainerMock();
        this.containerCreate = new AzureDlsGen2ContainerCreateRuntime();
    }

    @Test
    public void testInitializeNameContainerEmpty() {
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.Result.ERROR, validationResult.getStatus());
        assertEquals(messages.getMessage("error.ContainerEmpty"), validationResult.getMessage());
    }

    @Test
    public void testInitializeNameContainerNonAlphaNumeric() {
        properties.container.setValue("N@n_alpha_numeric#");
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.Result.ERROR, validationResult.getStatus());
        assertEquals(messages.getMessage("error.IncorrectName"), validationResult.getMessage());
    }

    @Test
    public void testInitializeNameContainerNonAllLowerCase() {
        properties.container.setValue("NonAllLowerCase");
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.Result.ERROR, validationResult.getStatus());
        assertEquals(messages.getMessage("error.UppercaseName"), validationResult.getMessage());
    }

    @Test
    public void testInitializeNameContainerLengthError() {
        properties.container.setValue("aa"); // container name length between 3 and 63
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.Result.ERROR, validationResult.getStatus());
        assertEquals(messages.getMessage("error.LengthError"), validationResult.getMessage());

        // generate 64 string name for the container witch is invalide
        properties.container.setValue(String.format("%0" + 64 + "d", 0)
                                              .replace("0", "a"));
        validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.Result.ERROR, validationResult.getStatus());
        assertEquals(messages.getMessage("error.LengthError"), validationResult.getMessage());
    }

    @Test
    public void testInitializeNameContainerValide() {
        properties.container.setValue("container-name-ok-14"); // container name length between 3 and 63
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.OK.getStatus(), validationResult.getStatus());
    }

    /**
     * The method {@link AzureDlsGen2ContainerCreateRuntime#runAtDriver(RuntimeContainer)} should not throw any
     * exception if the
     * dieOnError is not set to true.
     */
    @Test
    public void testRunAtDriverHandleStorageException() {

        properties.container.setValue("container-name-ok");
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.OK.getStatus(), validationResult.getStatus());
        containerCreate.blobService = blobService;

        try {

            when(blobService.createContainerIfNotExist(anyString(), any(PublicAccessType.class))).thenThrow(
                    new BlobStorageException("storage exception message", new HttpResponse(null) {

                        @Override
                        public int getStatusCode() {
                            return 500;
                        }

                        @Override
                        public String getHeaderValue(String name) {
                            return "headers.getValue(name)";
                        }

                        @Override
                        public HttpHeaders getHeaders() {
                            Map<String, String> headers = new HashMap<>();
                            headers.put("x-ms-error-code", "500");
                            return new HttpHeaders(headers);
                        }

                        @Override
                        public Flux<ByteBuffer> getBody() {
                            return Flux.empty();
                        }

                        @Override
                        public Mono<byte[]> getBodyAsByteArray() {
                            return Mono.just(new byte[0]);
                        }

                        @Override
                        public Mono<String> getBodyAsString() {
                            return Mono.just("");
                        }

                        @Override
                        public Mono<String> getBodyAsString(Charset charset) {
                            return Mono.just("");
                        }

                    }, new RuntimeException()));
            containerCreate.runAtDriver(runtimeContainer);

        } catch (BlobStorageException e) {
            fail("should handle this error correctly " + e.getMessage());
        }

    }


    @Test(expected = ComponentException.class)
    public void testRunAtDriverDieOnError() {

        properties.container.setValue("container-name-ok");
        properties.dieOnError.setValue(true);
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.OK.getStatus(), validationResult.getStatus());
        containerCreate.blobService = blobService;

        try {
            when(blobService.createContainerIfNotExist(anyString(), any(PublicAccessType.class)))
                    .thenThrow(
                            new BlobStorageException("storage exception message", null, new RuntimeException()));
            containerCreate.runAtDriver(runtimeContainer);

        } catch (BlobStorageException e) {
            fail("should not throw this exception" + e.getMessage());
        }

    }

    @Test
    public void testRunAtDriverValid() {

        properties.container.setValue("container-name-ok");
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.OK.getStatus(), validationResult.getStatus());

        try {
            when(blobService.createContainerIfNotExist(anyString(),
                                                       any(PublicAccessType.class))).thenReturn(true);
            doAnswer(new Answer<Void>() {

                @Override
                public Void answer(InvocationOnMock invocation) throws Throwable {
                    return null;
                }
            }).when(blobService);

            containerCreate.blobService = blobService;
            containerCreate.runAtDriver(runtimeContainer);
        } catch (BlobStorageException e) {
            fail("should not throw this exception" + e.getMessage());
        }

    }

    @Test
    public void testRunAtDriverContainerAllReadyCreated() {

        properties.container.setValue("container-name-ok");
        ValidationResult validationResult = containerCreate.initialize(runtimeContainer, properties);
        assertEquals(ValidationResult.OK.getStatus(), validationResult.getStatus());

        try {
            when(blobService.createContainerIfNotExist(anyString(),
                                                       any(PublicAccessType.class))).thenReturn(false);
            doAnswer(new Answer<Void>() {

                @Override
                public Void answer(InvocationOnMock invocation) throws Throwable {
                    return null;
                }
            }).when(blobService);

            containerCreate.blobService = blobService;
            containerCreate.runAtDriver(runtimeContainer);
        } catch (BlobStorageException e) {
            fail("should not throw this exception" + e.getMessage());
        }

    }

}
