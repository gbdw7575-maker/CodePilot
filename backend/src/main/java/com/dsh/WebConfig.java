package com.dsh;

import com.dsh.web.LocalAccessGuardFilter;
import com.dsh.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

    /** 本地访问与写请求防护：在来源检查通过后才进入幂等与业务逻辑。 */
    @Bean
    public FilterRegistrationBean<LocalAccessGuardFilter> localAccessGuardFilter(ObjectMapper mapper) {
        FilterRegistrationBean<LocalAccessGuardFilter> registration =
                new FilterRegistrationBean<>(new LocalAccessGuardFilter(mapper));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Integer.MIN_VALUE + 1000);
        return registration;
    }
}
