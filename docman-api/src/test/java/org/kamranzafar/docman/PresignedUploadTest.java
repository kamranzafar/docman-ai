/*
 *  Copyright 2026 Kamran Zafar
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.kamranzafar.docman;

import okhttp3.*;
import org.apache.commons.io.IOUtils;

import java.io.IOException;

public class PresignedUploadTest {
    public static void main(String[] args) throws IOException {
        OkHttpClient client = new OkHttpClient().newBuilder()
                .build();
        MediaType mediaType = MediaType.parse("text/plain");
        RequestBody body = RequestBody.create(mediaType,
                IOUtils.readFully(PresignedDownloadTest.class.getClassLoader().getResourceAsStream("test.txt"), 12));
        Request request = new Request.Builder()
                .url("http://localhost:9000/docman/356a2edd-5e06-44bd-83ae-45099a20972d/test.txt" +
                        "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260412%2Fus-east-1%2Fs3%2Faws4_request" +
                        "&X-Amz-Date=20260412T074634Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host" +
                        "&X-Amz-Signature=ebe8ab3c7510f547b98f3bad1c4d1a0c2f7587b99cc499fd17e00b4d35bb60a3")
                .method("PUT", body)
                .addHeader("Content-Type", "text/plain")
                .addHeader("Host", "minio")
                .build();

        Response response = client.newCall(request).execute();

        System.out.println(response.body().string());
    }
}
