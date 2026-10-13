package com.dsh.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 缓存请求体字节，供幂等摘要计算与 @RequestBody 重复读取。
 * 超过 maxBytes 抛出 IOException，由过滤器统一转为 413。
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyRequestWrapper(HttpServletRequest request, long maxBytes) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
        if (body.length > maxBytes) {
            throw new IOException("请求体超过 " + maxBytes + " 字节上限");
        }
    }

    public byte[] body() {
        return body;
    }

    /** 取缓存体；请求未被包装时直接读取（GET 等无体请求返回空数组）。 */
    public static byte[] bodyOf(HttpServletRequest request) {
        if (request instanceof CachedBodyRequestWrapper wrapper) {
            return wrapper.body;
        }
        try {
            return request.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取请求体失败", e);
        }
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return buffer.read();
            }

            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset charset = getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(getCharacterEncoding());
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }
}
