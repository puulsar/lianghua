package com.brotherc.aquant.sys.repository;

import com.brotherc.aquant.sys.entity.SysConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {

    SysConfig findByName(String name);
}