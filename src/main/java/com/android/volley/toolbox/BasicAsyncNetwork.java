/*
 * Copyright (C) 2020 The Android Open Source Project
 *
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
 */

package com.android.volley.toolbox;

import static com.android.volley.toolbox.NetworkUtility.logSlowRequests;

import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.android.volley.AsyncNetwork;
import com.android.volley.AuthFailureError;
import com.android.volley.Header;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.VolleyError;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

/** A network performing Volley requests over an {@link HttpStack}. */
public class BasicAsyncNetwork extends AsyncNetwork {

    private final ByteArrayPool mPool;

    /**
     * @param httpStack HTTP stack to be used
     * @param pool a buffer pool that improves GC performance in copy operations
     */
    private BasicAsyncNetwork(AsyncHttpStack httpStack, ByteArrayPool pool) {
        super(httpStack);
        mPool = pool;
    }

    /* Method to be called after a successful network request */
    private void onRequestSucceeded(
            final Request<?> request,
            final long requestStartMs,
            final HttpResponse httpResponse,
            final OnRequestComplete callback) {
        final int statusCode = httpResponse.getStatusCode();
        final List<Header> responseHeaders = httpResponse.getHeaders();
        // Handle cache validation.
        if (statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            long requestDuration = SystemClock.elapsedRealtime() - requestStartMs;
            callback.onSuccess(
                    NetworkUtility.getNotModifiedNetworkResponse(
                            request, requestDuration, responseHeaders));
            return;
        }

        byte[] responseContents = httpResponse.getContentBytes();
        if (responseContents == null && httpResponse.getContent() == null) {
            // Add 0 byte response as a way of honestly representing a
            // no-content request.
            responseContents = new byte[0];
        }

        if (responseContents != null) {
            onResponseRead(
                    requestStartMs,
                    statusCode,
                    httpResponse,
                    request,
                    callback,
                    responseHeaders,
                    responseContents);
            return;
        }

        // The underlying AsyncHttpStack does not support asynchronous reading of the response into
        // a byte array, so we need to submit a blocking task to copy the response from the
        // InputStream instead.
        final InputStream inputStream = httpResponse.getContent();
        Runnable run =
                new Runnable() {
                    @Override
                    public void run() {
                        byte[] finalResponseContents;
                        try {
                            finalResponseContents =
                                    NetworkUtility.inputStreamToBytes(
                                            inputStream, httpResponse.getContentLength(), mPool);
                        } catch (IOException e) {
                            onRequestFailed(
                                    request, callback, e, requestStartMs, httpResponse, null);
                            return;
                        }
                        onResponseRead(
                                requestStartMs,
                                statusCode,
                                httpResponse,
                                request,
                                callback,
                                responseHeaders,
                                finalResponseContents);
                    }
                };
        getBlockingExecutor().execute(run);
    }

    /* Method to be called after a failed network request */
    private void onRequestFailed(
            Request<?> request,
            OnRequestComplete callback,
            IOException exception,
            long requestStartMs,
            @Nullable HttpResponse httpResponse,
            @Nullable byte[] responseContents) {
        try {
            NetworkUtility.handleException(
                    request, exception, requestStartMs, httpResponse, responseContents);
        } catch (VolleyError volleyError) {
            callback.onError(volleyError);
            return;
        }
        performRequest(request, callback);
    }

    @Override
    public void performRequest(final Request<?> request, final OnRequestComplete callback) {
        if (getBlockingExecutor() == null) {
            throw new IllegalStateException(
                    "mBlockingExecuter must be set before making a request");
        }
        final long requestStartMs = SystemClock.elapsedRealtime();
        // Gather headers.
        final Map<String, String> additionalRequestHeaders =
                HttpHeaderParser.getCacheHeaders(request.getCacheEntry());
        getHttpStack()
                .executeRequest(
                        request,
                        additionalRequestHeaders,
                        new AsyncHttpStack.OnRequestComplete() {
                            @Override
                            public void onSuccess(HttpResponse httpResponse) {
                                onRequestSucceeded(request, requestStartMs, httpResponse, callback);
                            }

                            @Override
                            public void onAuthError(AuthFailureError authFailureError) {
                                callback.onError(authFailureError);
                            }

                            @Override
                            public void onError(IOException ioException) {
                                onRequestFailed(
                                        request,
                                        callback,
                                        ioException,
                                        requestStartMs,
                                        /* httpResponse= */ null,
                                        /* responseContents= */ null);
                            }
                        });
    }

    /* Helper method that determines what to do after byte[] is received */
    private void onResponseRead(
            long requestStartMs,
            int statusCode,
            HttpResponse httpResponse,
            Request<?> request,
            OnRequestComplete callback,
            List<Header> responseHeaders,
            byte[] responseContents) {
        // if the request is slow, log it.
        long requestLifetime = SystemClock.elapsedRealtime() - requestStartMs;
        logSlowRequests(requestLifetime, request, responseContents, statusCode);

        if (statusCode < 200 || statusCode > 299) {
            onRequestFailed(
                    request,
                    callback,
                    new IOException(),
                    requestStartMs,
                    httpResponse,
                    responseContents);
            return;
        }

        callback.onSuccess(
                new NetworkResponse(
                        statusCode,
                        responseContents,
                        /* notModified= */ false,
                        SystemClock.elapsedRealtime() - requestStartMs,
                        responseHeaders));
    }

    /**
     * Builder is used to build an instance of {@link BasicAsyncNetwork} from values configured by
     * the setters.
     */
    public static class Builder {
        private static final int DEFAULT_POOL_SIZE = 4096;
        @NonNull private AsyncHttpStack mAsyncStack;
        private ByteArrayPool mPool;

        public Builder(@NonNull AsyncHttpStack httpStack) {
            mAsyncStack = httpStack;
            mPool = null;
        }

        /**
         * Sets the ByteArrayPool to be used. If not set, it will default to a pool with the default
         * pool size.
         */
        public Builder setPool(ByteArrayPool pool) {
            mPool = pool;
            return this;
        }

        /** Builds the {@link com.android.volley.toolbox.BasicAsyncNetwork} */
        public BasicAsyncNetwork build() {
            if (mPool == null) {
                mPool = new ByteArrayPool(DEFAULT_POOL_SIZE);
            }
            return new BasicAsyncNetwork(mAsyncStack, mPool);
        }
    }
}