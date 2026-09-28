package edu.upc.dtak.aran.core.domain;

import java.sql.Driver;

public interface DriverDownloader {
    Driver loadDriver(String engine) throws Exception;
    void unloadDriver(String engine);
}
