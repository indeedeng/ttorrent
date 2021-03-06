/*
 * Copyright 2014 shevek.
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
package com.turn.ttorrent.tracker.client;

import com.google.common.io.Closeables;
import com.turn.ttorrent.protocol.bcodec.BEValue;
import com.turn.ttorrent.protocol.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.protocol.bcodec.StreamBDecoder;
import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.protocol.tracker.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPAnnounceResponseMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPTrackerErrorMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPTrackerMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.CheckForSigned;
import javax.annotation.Nonnull;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Announcer for HTTP trackers.
 *
 * @author shevek
 */
public class HTTPTrackerClient extends TrackerClient {

    protected static final Logger LOG = LoggerFactory.getLogger(HTTPTrackerClient.class);
    private CloseableHttpAsyncClient httpclient;

    public HTTPTrackerClient(@Nonnull PeerAddressProvider peerAddressProvider) {
        super(peerAddressProvider);
    }

    @Override
    public void start() throws Exception {
        super.start();
        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(3000)
                .setConnectTimeout(3000)
                .build();
        httpclient = HttpAsyncClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        httpclient.start();
    }

    /**
     * This method is not thread safe.
     *
     * However, it is guarded by the lock in com.turn.ttorrent.client.Client, so
     * it's never called in a manner which would be unsafe.
     *
     * @throws Exception
     */
    @Override
    public void stop() throws Exception {
        if (httpclient != null)
            httpclient.close();
        httpclient = null;
        super.stop();
    }

    private class HttpResponseCallback implements FutureCallback<HttpResponse> {

        private final AnnounceResponseListener listener;
        private final HttpUriRequest request;
        private final URI tracker;
        private final TrackerMessage.AnnounceEvent event;

        public HttpResponseCallback(AnnounceResponseListener listener, HttpUriRequest request, URI tracker, TrackerMessage.AnnounceEvent event) {
            this.listener = listener;
            this.request = request;
            this.tracker = tracker;
            this.event = event;
        }

        @Override
        public void completed(HttpResponse response) {
            if (LOG.isTraceEnabled())
                LOG.trace("Completed: {} -> {}", request.getRequestLine(), response.getStatusLine());
            try {
                HTTPTrackerMessage message = toMessage(response, -1);
                if (message != null)
                    handleTrackerAnnounceResponse(listener, tracker, event, message, false);
            } catch (Exception e) {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failed to handle announce response", e);
                failed(e);
            }
        }

        @Override
        public void failed(Exception e) {
            // This error wasn't necessarily reported elsewhere.
            if (LOG.isDebugEnabled())
                LOG.debug("Failed: {} -> {}", request.getRequestLine(), e);
            // TODO: Pass failure back to TrackerHandler.
            // LOG.trace("Failed: " + request.getRequestLine(), e);
            listener.handleAnnounceFailed(tracker, event, "HTTP failed: " + e);
        }

        @Override
        public void cancelled() {
            LOG.trace("Cancelled: {}", request.getRequestLine());
        }
    }

    /**
     * Build, send and process a tracker announce request.
     *
     * <p>
     * This function first builds an announce request for the specified event
     * with all the required parameters. Then, the request is made to the
     * tracker and the response analyzed.
     * </p>
     *
     * <p>
     * All registered {@link AnnounceResponseListener} objects are then fired
     * with the decoded payload.
     * </p>
     *
     * @param event The announce event type (can be AnnounceEvent.NONE for
     * periodic updates).
     * @param inhibitEvents Prevent event listeners from being notified.
     */
    @Override
    public void announce(
            AnnounceResponseListener listener,
            TorrentMetadataProvider torrent,
            URI tracker,
            TrackerMessage.AnnounceEvent event,
            boolean inhibitEvents) throws AnnounceException {
        LOG.info("Announcing{} to tracker {} with {}U/{}D/{}L bytes...",
                new Object[]{
            TrackerClient.formatAnnounceEvent(event),
            tracker,
            torrent.getUploaded(),
            torrent.getDownloaded(),
            torrent.getLeft()
        });

        try {
            HTTPAnnounceRequestMessage message =
                    new HTTPAnnounceRequestMessage(
                    torrent.getInfoHash(),
                    getLocalPeerId(), getLocalPeerAddresses(),
                    torrent.getUploaded(), torrent.getDownloaded(), torrent.getLeft(),
                    true, false, event, AnnounceRequestMessage.DEFAULT_NUM_WANT);
            URI target = message.toURI(tracker);
            HttpGet request = new HttpGet(target);
            HttpResponseCallback callback = new HttpResponseCallback(listener, request, tracker, event);
            httpclient.execute(request, callback);
        } catch (URISyntaxException mue) {
            throw new AnnounceException("Invalid announce URI ("
                    + mue.getMessage() + ")", mue);
        } catch (IOException ioe) {
            throw new AnnounceException("Error building announce request ("
                    + ioe.getMessage() + ")", ioe);
        }
    }

    // The tracker may return valid BEncoded data even if the status code
    // was not a 2xx code. On the other hand, it may return garbage.
    @CheckForNull
    public static HTTPTrackerMessage toMessage(@Nonnull HttpResponse response, @CheckForSigned long maxContentLength)
            throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) // Usually 204-no-content, etc.
            return null;
        try {
            if (maxContentLength >= 0) {
                long contentLength = entity.getContentLength();
                if (contentLength >= 0)
                    if (contentLength > maxContentLength)
                        throw new IllegalArgumentException("ContentLength was too big: " + contentLength + ": " + response);
            }

            InputStream in = entity.getContent();
            if (in == null)
                return null;
            try {
                StreamBDecoder decoder = new StreamBDecoder(in);
                BEValue value = decoder.bdecodeMap();
                Map<String, BEValue> params = value.getMap();
                // TODO: "warning message"
                if (params.containsKey("failure reason"))
                    return HTTPTrackerErrorMessage.fromBEValue(params);
                else
                    return HTTPAnnounceResponseMessage.fromBEValue(params);
            } finally {
                Closeables.close(in, true);
            }
        } catch (InvalidBEncodingException e) {
            throw new IOException("Failed to parse response " + response, e);
        } catch (TrackerMessage.MessageValidationException e) {
            throw new IOException("Failed to parse response " + response, e);
        } finally {
            EntityUtils.consumeQuietly(entity);
        }
    }
}
