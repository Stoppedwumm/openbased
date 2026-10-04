package org.openbased.media;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtworkRepository extends JpaRepository<Artwork, String> {

    List<Artwork> findByMediaId(String mediaId);
}
