package com.yuwatang.reservation;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ProductionChecks {
    final String mode,url;
    ProductionChecks(@Value("${app.mode}") String mode,@Value("${spring.datasource.url}") String url){this.mode=mode;this.url=url;}
    @PostConstruct void verify(){
        // The MariaDB JDBC driver also connects to the existing MySQL 5.7 host.
        if(!"local".equals(mode)&&!url.startsWith("jdbc:mysql:")&&!url.startsWith("jdbc:mariadb:"))
            throw new IllegalStateException("Production requires MySQL; the local H2 file cannot serve multiple application instances.");
    }
}
