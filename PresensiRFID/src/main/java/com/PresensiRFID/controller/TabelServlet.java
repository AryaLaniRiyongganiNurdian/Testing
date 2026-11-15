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

/**
 * GET /api/tabel?jenis=log|mahasiswa|dosen&page=N&sort=...&dir=asc|desc&kolom=...&cari=...
 *
 * Nama kolom urutan dan kolom pencarian tidak pernah diambil langsung dari request,
 * hanya dipilih dari daftar tetap di bawah (whitelist), sehingga aman dari SQL injection.
 * Pencarian memakai awalan teks (LIKE 'abc%') agar index B-tree terpakai.
 */
@WebServlet("/api/tabel")
public class TabelServlet extends HttpServlet {

    private static final int UKURAN_HALAMAN = 20;

    private static final String SQL_LOG_LUAR =
            "SELECT l.id_log, l.nim, "
            + "COALESCE(m.nama, '(tidak ada di data mahasiswa)') AS nama, "
            + "COALESCE(r.nama_ruangan, l.id_ruangan) AS nama_ruangan, "
            + "DATE_FORMAT(l.waktu_masuk, '%Y-%m-%d %H:%i:%s') AS masuk, "
            + "DATE_FORMAT(l.waktu_keluar, '%Y-%m-%d %H:%i:%s') AS keluar, "
            + "TIME_FORMAT(l.durasi_kunjungan, '%H:%i:%s') AS durasi "
            + "FROM ({INNER}) k "
            + "JOIN log_presensi l ON l.id_log = k.id_log "
            + "LEFT JOIN mahasiswa m ON m.nim = l.nim "
            + "LEFT JOIN ruangan r ON r.id_ruangan = l.id_ruangan "
            + "ORDER BY ";

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
        String nim = bersih(request.getParameter("nim"));      // pencarian persis, dipakai form Input Manual
        String sort = bersih(request.getParameter("sort")).toLowerCase();
        String dirParam = bersih(request.getParameter("dir")).toLowerCase();
        String kolom = bersih(request.getParameter("kolom")).toLowerCase();
        String cari = bersih(request.getParameter("cari"));
        if (cari.length() > 100) {
            cari = cari.substring(0, 100);
        }
        int halaman = angka(request.getParameter("page"));

        // Tanpa parameter dir: log terbaru dulu (perilaku lama), tabel lain urut naik
        boolean turun = dirParam.isEmpty() ? jenis.equals("log") : dirParam.equals("desc");
        String d = turun ? "DESC" : "ASC";

        try (Connection conn = Db.get()) {
            Map<String, Object> hasil;
            switch (jenis) {
                case "log":
                    hasil = tabelLog(conn, halaman, sort, d, kolom, cari);
                    break;
                case "mahasiswa":
                    hasil = tabelMahasiswa(conn, halaman, sort, d, kolom, cari, nim);
                    break;
                case "dosen":
                    hasil = tabelDosen(conn, halaman, sort, d, kolom, cari);
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

    // ------------------------------------------------------------------ mahasiswa
    private static Map<String, Object> tabelMahasiswa(Connection conn, int halaman, String sort, String d,
                                                      String kolom, String cari, String nim) throws SQLException {
        String urut;
        switch (sort) {
            case "nama":  urut = "t.nama " + d + ", t.nim " + d; break;
            case "kelas": urut = "t.kelas " + d + ", t.nim " + d; break;
            case "kartu": urut = "t.uid_rfid " + d + ", t.nim " + d; break;
            default:      urut = "t.nim " + d; break;
        }

        String kondisi = "";
        List<Object> param = new ArrayList<>();
        if (!nim.isEmpty()) {
            kondisi = "t.nim = ?";
            param.add(nim);
            halaman = 1;
        } else if (!cari.isEmpty()) {
            String kol;
            switch (kolom) {
                case "nama":  kol = "t.nama"; break;
                case "kelas": kol = "t.kelas"; break;
                case "kartu": kol = "t.uid_rfid"; break;
                default:      kol = "t.nim"; break;
            }
            kondisi = kol + " LIKE ?";
            param.add(awalan(cari));
        }

        String luar = "SELECT t.nim, t.nama, t.kelas, t.uid_rfid FROM ({INNER}) k "
                + "JOIN mahasiswa t ON t.nim = k.nim ORDER BY " + urut;
        return ambilHalaman(conn, "mahasiswa", "mahasiswa t", kondisi, param, "t.nim", urut, luar,
                TabelServlet::barisMahasiswa, halaman);
    }

    // ---------------------------------------------------------------------- dosen
    private static Map<String, Object> tabelDosen(Connection conn, int halaman, String sort, String d,
                                                  String kolom, String cari) throws SQLException {
        String urut;
        switch (sort) {
            case "nama":  urut = "t.nama " + d + ", t.id_dosen " + d; break;
            case "email": urut = "t.email " + d; break;
            default:      urut = "t.nip " + d; break;
        }

        String kondisi = "";
        List<Object> param = new ArrayList<>();
        if (!cari.isEmpty()) {
            String kol;
            switch (kolom) {
                case "nama":  kol = "t.nama"; break;
                case "email": kol = "t.email"; break;
                default:      kol = "t.nip"; break;
            }
            kondisi = kol + " LIKE ?";
            param.add(awalan(cari));
        }

        String luar = "SELECT t.nip, t.nama, t.email FROM ({INNER}) k "
                + "JOIN dosen t ON t.id_dosen = k.id_dosen ORDER BY " + urut;
        return ambilHalaman(conn, "dosen", "dosen t", kondisi, param, "t.id_dosen", urut, luar,
                TabelServlet::barisDosen, halaman);
    }

    // ------------------------------------------------------------------------ log
    private static Map<String, Object> tabelLog(Connection conn, int halaman, String sort, String d,
                                                String kolom, String cari) throws SQLException {
        boolean perluM = false;   // tabel mahasiswa dibutuhkan oleh pencarian
        boolean perluR = false;   // tabel ruangan dibutuhkan oleh pencarian
        String kondisi = "";
        List<Object> param = new ArrayList<>();
        if (!cari.isEmpty()) {
            switch (kolom) {
                case "id":
                    try {
                        param.add(Long.parseLong(cari));
                        kondisi = "l.id_log = ?";
                    } catch (NumberFormatException e) {
                        kondisi = "1 = 0";
                    }
                    break;
                case "nama":
                    kondisi = "m.nama LIKE ?";
                    param.add(awalan(cari));
                    perluM = true;
                    break;
                case "ruangan":
                    kondisi = "r.nama_ruangan LIKE ?";
                    param.add(awalan(cari));
                    perluR = true;
                    break;
                default:
                    kondisi = "l.nim LIKE ?";
                    param.add(awalan(cari));
                    break;
            }
        }

        String dari;
        String urutDalam;
        String urutLuar;
        switch (sort) {
            case "mahasiswa":
                // Telusuri mahasiswa lewat index nama, lalu log lewat index nim
                dari = "mahasiswa m STRAIGHT_JOIN log_presensi l ON l.nim = m.nim"
                        + (perluR ? " JOIN ruangan r ON r.id_ruangan = l.id_ruangan" : "");
                urutDalam = "m.nama " + d + ", m.nim " + d;
                urutLuar = urutDalam + ", l.id_log ASC";
                break;
            case "ruangan":
                dari = "ruangan r STRAIGHT_JOIN log_presensi l ON l.id_ruangan = r.id_ruangan"
                        + (perluM ? " JOIN mahasiswa m ON m.nim = l.nim" : "");
                urutDalam = "r.nama_ruangan " + d;
                urutLuar = urutDalam + ", l.id_log ASC";
                break;
            case "masuk":
                dari = dariLog(perluM, perluR);
                urutDalam = "l.waktu_masuk " + d + ", l.id_log " + d;
                urutLuar = urutDalam;
                break;
            case "keluar":
                dari = dariLog(perluM, perluR);
                urutDalam = "l.waktu_keluar " + d + ", l.id_log " + d;
                urutLuar = urutDalam;
                break;
            default:
                dari = dariLog(perluM, perluR);
                urutDalam = "l.id_log " + d;
                urutLuar = urutDalam;
                break;
        }

        return ambilHalaman(conn, "log_presensi", dari, kondisi, param, "l.id_log", urutDalam,
                SQL_LOG_LUAR + urutLuar, TabelServlet::barisLog, halaman);
    }

    private static String dariLog(boolean perluM, boolean perluR) {
        return "log_presensi l"
                + (perluM ? " JOIN mahasiswa m ON m.nim = l.nim" : "")
                + (perluR ? " JOIN ruangan r ON r.id_ruangan = l.id_ruangan" : "");
    }

    // ----------------------------------------------------------------- inti paging
    /**
     * Teknik "deferred join": subquery dalam hanya mengambil kunci baris (index saja) dengan
     * ORDER BY dan LIMIT, lalu detail baris diambil untuk 20 kunci tersebut. Ini menjaga
     * halaman yang dalam (misalnya halaman 50.000) tetap ringan.
     */
    private static Map<String, Object> ambilHalaman(Connection conn, String tabelCache, String dari,
                                                     String kondisi, List<Object> param, String kolomKunci,
                                                     String urutDalam, String sqlLuar, Pemeta pemeta,
                                                     int halaman) throws SQLException {
        String where = kondisi.isEmpty() ? "" : " WHERE " + kondisi;

        long total;
        if (kondisi.isEmpty()) {
            total = CacheTotal.ambil(conn, tabelCache);
        } else {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + dari + where)) {
                isiParam(ps, param);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
        }

        int totalHalaman = (int) Math.max(1, (total + UKURAN_HALAMAN - 1) / UKURAN_HALAMAN);
        int hal = Math.min(Math.max(halaman, 1), totalHalaman);

        String dalam = "SELECT " + kolomKunci + " FROM " + dari + where
                + " ORDER BY " + urutDalam + " LIMIT ? OFFSET ?";
        String sql = sqlLuar.replace("{INNER}", dalam);

        List<Map<String, Object>> baris = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = isiParam(ps, param);
            ps.setInt(i++, UKURAN_HALAMAN);
            ps.setLong(i, (long) (hal - 1) * UKURAN_HALAMAN);
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

    private static int isiParam(PreparedStatement ps, List<Object> param) throws SQLException {
        int i = 1;
        for (Object o : param) {
            ps.setObject(i++, o);
        }
        return i;
    }

    /** Awalan untuk LIKE, dengan karakter khusus (\ % _) di-escape. */
    private static String awalan(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
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