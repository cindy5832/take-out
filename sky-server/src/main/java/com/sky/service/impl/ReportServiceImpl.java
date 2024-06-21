package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WorkspaceService workspaceService;

    @Override
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        // 存放begin~end的所有日期
        List<LocalDate> dateList = getLocalDatesList(begin, end);

        // 存放每日營業額
        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            // 查詢date日期對應的營業額數據，狀態為"已完成"的訂單金額
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN); // 00:00:00
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX); // 23:59:59
            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover = orderMapper.sumByMap(map);
            turnover = turnover == null ? 0.0 : turnover;
            turnoverList.add(turnover);
        }

        String dates = StringUtils.join(dateList, ",");
        String money = StringUtils.join(turnoverList, ",");

        return TurnoverReportVO.builder()
                .dateList(dates)
                .turnoverList(money)
                .build();
    }

    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        // 存放begin~end的所有日期
        List<LocalDate> dateList = getLocalDatesList(begin, end);

        // 存放每天的新增用戶數量
        List<Integer> newUserList = new ArrayList<>();
        // 存放每天的總用戶數量
        List<Integer> totalUserList = new ArrayList<>();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            Map map = new HashMap();
            map.put("end", endTime);
            Integer totalUser = userMapper.countByMap(map);

            map.put("begin", beginTime);
            Integer newUser = userMapper.countByMap(map);

            totalUserList.add(totalUser);
            newUserList.add(newUser);
        }


        String dates = StringUtils.join(dateList, ",");
        String newUser = StringUtils.join(newUserList, ",");
        String totalUser = StringUtils.join(totalUserList, ",");

        return UserReportVO.builder()
                .dateList(dates)
                .newUserList(newUser)
                .totalUserList(totalUser)
                .build();
    }

    @Override
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        // 存放begin~end的所有日期
        List<LocalDate> dateList = getLocalDatesList(begin, end);

        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            // 查詢訂單總數
            Integer orderCount = getOrderCount(beginTime, endTime, null);
            orderCountList.add(orderCount);

            // 查詢每天有效訂單數
            Integer validOrderCount = getOrderCount(beginTime, endTime, Orders.COMPLETED);
            validOrderCountList.add(validOrderCount);
        }

        Integer totalOrderCount = orderCountList.stream().reduce(Integer::sum).get();
        Integer totalValidCount = validOrderCountList.stream().reduce(Integer::sum).get();

        // 計算訂單完成率
        Double orderCompleteRate = 0.0;
        if (totalOrderCount != 0) {
            orderCompleteRate = totalValidCount.doubleValue() / totalOrderCount;
        }

        String dates = StringUtils.join(dateList, ",");
        String orderCount = StringUtils.join(orderCountList, ",");
        String validCount = StringUtils.join(validOrderCountList, ",");

        return OrderReportVO.builder()
                .dateList(dates)
                .orderCountList(orderCount)
                .validOrderCountList(validCount)
                .validOrderCount(totalValidCount)
                .totalOrderCount(totalOrderCount)
                .orderCompletionRate(orderCompleteRate)
                .build();
    }

    @Override
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(beginTime, endTime);

        List<String> names = salesTop10.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList());
        String nameList = StringUtils.join(names, ",");
        List<Integer> numbers = salesTop10.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList());
        String numberList = StringUtils.join(numbers, ",");
        return SalesTop10ReportVO
                .builder()
                .nameList(nameList)
                .numberList(numberList)
                .build();
    }

    @Override
    public void exportBusinessData(HttpServletResponse response) {
        // 查詢近30天數據
        LocalDate dateBegin = LocalDate.now().minusDays(30);
        LocalDate dateEnd = LocalDate.now().minusDays(1);
        // 查詢概覽數據
        BusinessDataVO businessDataVO = workspaceService.getBusinessData(LocalDateTime.of(dateBegin, LocalTime.MIN), LocalDateTime.of(dateEnd, LocalTime.MAX));
        // 透過POI將數據寫入excel
        InputStream in = this.getClass().getClassLoader().getResourceAsStream("template/Operational_data_template.xlsx");
        // 基於模板文件創建一個新的excel文件
        try {
            XSSFWorkbook excel = new XSSFWorkbook(in);
            // 添加數據  -- 時間
            XSSFSheet sheet = excel.getSheet("Sheet1");
            sheet.getRow(1).getCell(1).setCellValue("time: " + dateBegin + " ~ " + dateEnd);

            XSSFRow row = sheet.getRow(3);
            row.getCell(2).setCellValue(businessDataVO.getTurnover());
            row.getCell(4).setCellValue(businessDataVO.getOrderCompletionRate());
            row.getCell(6).setCellValue(businessDataVO.getNewUsers());

            row = sheet.getRow(4);
            row.getCell(2).setCellValue(businessDataVO.getValidOrderCount());
            row.getCell(4).setCellValue(businessDataVO.getUnitPrice());

            // 明細數據
            for (int i = 0; i < 30; i++) {
                LocalDate date = dateBegin.plusDays(1);
                // 查詢每天的營業數據
                BusinessDataVO businessData = workspaceService.getBusinessData(LocalDateTime.of(date, LocalTime.MIN), LocalDateTime.of(date, LocalTime.MAX));
                row = sheet.getRow(7 + i);
                row.getCell(1).setCellValue(date.toString());
                row.getCell(2).setCellValue(businessData.getTurnover());
                row.getCell(3).setCellValue(businessData.getValidOrderCount());
                row.getCell(4).setCellValue(businessData.getOrderCompletionRate());
                row.getCell(5).setCellValue(businessData.getUnitPrice());
                row.getCell(6).setCellValue(businessData.getNewUsers());
            }

            // 通過輸出流將excel下載到客戶端
            ServletOutputStream out = response.getOutputStream();
            excel.write(out);
            // 關閉資源
            out.close();
            excel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private List<LocalDate> getLocalDatesList(LocalDate begin, LocalDate end) {
        // 存放begin~end的所有日期
        List<LocalDate> dateList = new ArrayList<>();
        dateList.add(begin);
        while (!begin.equals(end)) {
            // 日期計算
            begin = begin.plusDays(1);
            dateList.add(begin);
        }
        return dateList;
    }

    private Integer getOrderCount(LocalDateTime begin, LocalDateTime end, Integer status) {
        Map map = new HashMap();
        map.put("begin", begin);
        map.put("end", end);
        map.put("status", status);
        return orderMapper.countByMap(map);
    }
}
