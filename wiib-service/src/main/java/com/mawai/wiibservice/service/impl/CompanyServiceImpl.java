package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.Company;
import com.mawai.wiibservice.mapper.CompanyMapper;
import com.mawai.wiibservice.service.CompanyService;
import org.springframework.stereotype.Service;

@Service
public class CompanyServiceImpl extends ServiceImpl<CompanyMapper, Company> implements CompanyService {
}
