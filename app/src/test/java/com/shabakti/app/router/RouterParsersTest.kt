package com.shabakti.app.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterParsersTest {

    private val huaweiWithSig = """
        function USERDevice(Domain,IpAddr,MacAddr,Port,IpType,DevType,DevStatus,PortType,Time,HostName,IPv4Enabled,IPv6Enabled,DeviceType,UserDevAlias,UserSpecifiedDeviceType,LeaseTimeRemaining)
        { this.Domain = Domain; }
        var UserDevinfo = new Array(new USERDevice("InternetGatewayDevice\x2eLANDevice\x2e1\x2eX_HW_UserDev\x2e1","192\x2e168\x2e1\x2e5","a4\x3a50\x3a46\x3aaa\x3abb\x3acc","SSID1","DHCP","","Online","WIFI","10\x3a00","Galaxy\x2dS21","1","0","","","",""),new USERDevice("InternetGatewayDevice\x2eLANDevice\x2e1\x2eX_HW_UserDev\x2e2","192\x2e168\x2e1\x2e7","00\x3a11\x3a22\x3a33\x3a44\x3a55","LAN1","DHCP","","Offline","ETH","0","DESKTOP\x2d1","1","0","","","",""),null);
    """.trimIndent()

    private val huaweiNoSig = """
        var UserDevinfo = new Array(new USERDevice("x","192.168.100.9","3C:2E:FF:01:02:03","SSID1","DHCP","","Online","WIFI","1","iPhone","1"),null);
    """.trimIndent()

    @Test
    fun huaweiParsesWithSignature() {
        val d = RouterParsers.parseHuaweiUserDevices(huaweiWithSig)
        assertEquals(2, d.size)
        assertEquals("A4:50:46:AA:BB:CC", d[0].mac)
        assertEquals("192.168.1.5", d[0].ip)
        assertEquals("Galaxy-S21", d[0].hostName)
        assertTrue(d[0].online)
        assertEquals(true, d[0].wifi)
        assertEquals("DESKTOP-1", d[1].hostName)
        assertEquals(false, d[1].online)
        assertEquals(false, d[1].wifi)
    }

    @Test
    fun huaweiParsesWithoutSignature() {
        val d = RouterParsers.parseHuaweiUserDevices(huaweiNoSig)
        assertEquals(1, d.size)
        assertEquals("iPhone", d[0].hostName)
        assertEquals("192.168.100.9", d[0].ip)
    }

    @Test
    fun huaweiToken() {
        assertEquals("abc123", RouterParsers.parseHuaweiToken("﻿abc123\n"))
        val long = "x".repeat(10) + "t".repeat(48)
        assertEquals("t".repeat(48), RouterParsers.parseHuaweiToken(long))
    }

    @Test
    fun zteTokensAndErrors() {
        assertEquals("123456", RouterParsers.parseZteSessionToken("""{"lockingTime":0,"sess_token":"123456","promptMsg":""}"""))
        assertEquals("59469541", RouterParsers.parseZteLoginToken("<ajax_response_xml_root>59469541</ajax_response_xml_root>"))
        assertEquals("SessionTimeout", RouterParsers.zteError("<ajax_response_xml_root><IF_ERRORSTR>SessionTimeout</IF_ERRORSTR></ajax_response_xml_root>"))
        assertNull(RouterParsers.zteError("<ajax_response_xml_root><IF_ERRORSTR>SUCC</IF_ERRORSTR></ajax_response_xml_root>"))
        assertEquals(1, RouterParsers.jsonInt("""{"login_need_refresh":1,"loginErrMsg":""}""", "login_need_refresh"))
        assertEquals("", RouterParsers.jsonString("""{"login_need_refresh":1,"loginErrMsg":""}""", "loginErrMsg"))
    }

    @Test
    fun zteDevices() {
        val xml = """<ajax_response_xml_root><IF_ERRORSTR>SUCC</IF_ERRORSTR>
            <OBJ_ACCESSDEV_ID>
              <Instance><ParaName>_InstID</ParaName><ParaValue>DEV.HOST.1</ParaValue>
                <ParaName>MACAddress</ParaName><ParaValue>f8:e9:03:aa:bb:cc</ParaValue>
                <ParaName>IPAddress</ParaName><ParaValue>192.168.1.3</ParaValue>
                <ParaName>HostName</ParaName><ParaValue>Redmi-Note-12</ParaValue>
                <ParaName>AliasName</ParaName><ParaValue>SSID1</ParaValue></Instance>
              <Instance><ParaName>MACAddress</ParaName><ParaValue>00:aa:bb:cc:dd:ee</ParaValue>
                <ParaName>IPAddress</ParaName><ParaValue>192.168.1.4</ParaValue>
                <ParaName>HostName</ParaName><ParaValue></ParaValue>
                <ParaName>AliasName</ParaName><ParaValue>LAN2</ParaValue>
                <ParaName>Active</ParaName><ParaValue>0</ParaValue></Instance>
            </OBJ_ACCESSDEV_ID></ajax_response_xml_root>"""
        val d = RouterParsers.parseZteDevices(xml, null)
        assertEquals(2, d.size)
        assertEquals("F8:E9:03:AA:BB:CC", d[0].mac)
        assertEquals("Redmi-Note-12", d[0].hostName)
        assertEquals(true, d[0].wifi)
        assertNull(d[1].hostName)
        assertEquals(false, d[1].wifi)
        assertEquals(false, d[1].online)
    }

    @Test
    fun brandDetection() {
        assertEquals(RouterBrand.HUAWEI_ONT, RouterParsers.detectBrand("<script src=/asp/GetRandCount.asp>", ""))
        assertEquals(RouterBrand.ZTE, RouterParsers.detectBrand("${'$'}.post('/?_type=loginData&_tag=login_entry')", ""))
        assertEquals(RouterBrand.ZTE_LEGACY, RouterParsers.detectBrand("getObj(\"Frm_Logintoken\").value = \"3\"", ""))
        assertEquals(RouterBrand.TPLINK, RouterParsers.detectBrand("<title>TP-LINK</title>", ""))
        assertEquals(RouterBrand.UNKNOWN, RouterParsers.detectBrand("<html></html>", "Server: lighttpd"))
    }

    @Test
    fun sha() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            RouterParsers.sha256Hex("hello"),
        )
    }
}
