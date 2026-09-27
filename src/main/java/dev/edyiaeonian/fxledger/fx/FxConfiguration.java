package dev.edyiaeonian.fxledger.fx;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FxProperties.class)
class FxConfiguration {}
