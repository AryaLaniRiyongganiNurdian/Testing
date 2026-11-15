package com.absensi.controller;

import com.absensi.util.CacheTotal;
import com.absensi.util.Db;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/api/tabel")
public class TabelServlet extends HttpServlet {

    private static final int UKURAN_HALAMAN = 20;

    private static final String SQL_LOG =
            "SELECT l.id_log, l.nim, "
            + "COALESCE(m.nama, '(tidak ada di data mahasiswa)') AS nama, "
            + "COALESCE(r.nama_ruangan, l.id_ruangan) AS nama_ruangan, "
            + "DATE_FORMAT(l.waktu_masuk, '%Y-%m-%d %H:%i:%s') AS masuk, "
            + "DATE_FORMAT(l.waktu_keluar, '%Y-%m-%d %H:%i:%s') AS keluar, "
            + "TIME_FORMAT(l.durasi_kunjungan, '%H:%i:%s') AS durasi "
            + "FROM (SELECT id_log, nim, id_ruangan, waktu_masuk, waktu_keluar, durasi_kunjungan "
            + "      FROM log_presensi ORDER BY id_log DESC LIMIT ? OFFSET ?) l "
            + "LEFT JOIN mahasiswa m ON m.nim = l.nim "
            + "LEFT JOIN ruangan r ON r.id_ruangan = l.id_ruangan "
            + "ORDER BY l.id_log DESC";

    private interface Pemeta {
        Map<String, Object> petakan(ResultSet rs) throws SQLException;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!Auth.isLoggedIn(request)) {
            kirimError(response, HttpServletResponse.SC_UNAUTHORIZED, "Silakan login terlebih dahulu.");
            return;
        }

        String jenis = bersih(request.getParameter("jenis"));
        String nim = bersih(request.getParameter("nim"));
        int halaman = angka(request.getParameter("page"));

        try (Connection conn = Db.get()) {
            Map<String, Object> hasil;
            switch (jenis) {
                case "log":
                    hasil = ambilHalaman(conn, "log_presensi", SQL_LOG, null,
                            TabelServlet::barisLog, halaman);
                    break;
                case "mahasiswa":
                    if (nim.isEmpty()) {
                        hasil = ambilHalaman(conn, "mahasiswa",
                                "SELECT nim, nama, kelas, uid_rfid FROM mahasiswa ORDER BY nim LIMIT ? OFFSET ?",
                                null, TabelServlet::barisMahasiswa, halaman);
                    } else {
                        hasil = ambilHalaman(conn, "mahasiswa",
                                "SELECT nim, nama, kelas, uid_rfid FROM mahasiswa WHERE nim = ? ORDER BY nim LIMIT ? OFFSET ?",
                                nim, TabelServlet::barisMahasiswa, 1);
                    }
                    break;
                case "dosen":
                    hasil = ambilHalaman(conn, "dosen",
                            "SELECT nip, nama, email FROM dosen ORDER BY id_dosen LIMIT ? OFFSET ?",
                            null, TabelServlet::barisDosen, halaman);
                    break;
                default:
                    kirimError(response, HttpServletResponse.SC_BAD_REQUEST, "Jenis tabel tidak dikenal.");
                    return;
            }
            JsonUtil.write(response, HttpServletResponse.SC_OK, hasil);
        } catch (SQLException e) {
            log("Error tabel", e);
            kirimError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static Map<String, Object> ambilHalaman(Connection conn, String tabel, String sqlData,
                                                     String filterNim, Pemeta pemeta, int halaman)
            throws SQLException {
        long total;
        if (filterNim == null) {
            total = CacheTotal.ambil(conn, tabel);
        } else {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + tabel + " WHERE nim = ?")) {
                ps.setString(1, filterNim);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
        }

        int totalHalaman = (int) Math.max(1, (total + UKURAN_HALAMAN - 1) / UKURAN_HALAMAN);
        int hal = Math.min(Math.max(halaman, 1), totalHalaman);

        List<Map<String, Object>> baris = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sqlData)) {
            int i = 1;
            if (filterNim != null) {
                ps.setString(i++, filterNim);
            }
            ps.setInt(i++, UKURAN_HALAMAN);
            ps.setInt(i, (hal - 1) * UKURAN_HALAMAN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    baris.add(pemeta.petakan(rs));
                }
            }
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("status", "success");
        hasil.put("total", total);
        hasil.put("page", hal);
        hasil.put("totalPages", totalHalaman);
        hasil.put("rows", baris);
        return hasil;
    }

    private static Map<String, Object> barisLog(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id_log"));
        row.put("nim", rs.getString("nim"));
        row.put("nama", rs.getString("nama"));
        row.put("ruangan", rs.getString("nama_ruangan"));
        row.put("masuk", rs.getString("masuk"));
        row.put("keluar", rs.getString("keluar"));
        row.put("durasi", rs.getString("durasi"));
        return row;
    }

    private static Map<String, Object> barisMahasiswa(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nim", rs.getString("nim"));
        row.put("nama", rs.getString("nama"));
        row.put("kelas", rs.getString("kelas"));
        row.put("uid", rs.getString("uid_rfid"));
        return row;
    }

    private static Map<String, Object> barisDosen(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nip", rs.getString("nip"));
        row.put("nama", rs.getString("nama"));
        row.put("email", rs.getString("email"));
        return row;
    }

    private static void kirimError(HttpServletResponse response, int status, String pesan) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("status", "error");
        err.put("message", pesan);
        JsonUtil.write(response, status, err);
    }

    private static String bersih(String s) {
        return s == null ? "" : s.trim();
    }

    private static int angka(String s) {
        try {
            return Integer.parseInt(s == null ? "1" : s.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}