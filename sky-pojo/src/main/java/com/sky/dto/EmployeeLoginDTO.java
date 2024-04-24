package com.sky.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel(description = "員工登入時傳遞的數據模型")
public class EmployeeLoginDTO implements Serializable {

    @ApiModelProperty("用戶名")
    private String username;

    @ApiModelProperty("密碼")
    private String password;

}
