package com.example.llmgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LlmgatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(LlmgatewayApplication.class, args);
	}

}
