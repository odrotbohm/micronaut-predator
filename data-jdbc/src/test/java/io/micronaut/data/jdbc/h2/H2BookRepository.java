package io.micronaut.data.jdbc.h2;

import io.micronaut.data.annotation.Join;
import io.micronaut.data.exceptions.EmptyResultException;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.jdbc.runtime.JdbcOperations;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.tck.entities.Author;
import io.micronaut.data.tck.entities.Book;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@JdbcRepository(dialect = Dialect.H2)
public abstract class H2BookRepository extends io.micronaut.data.tck.repositories.BookRepository {
    private final JdbcOperations jdbcOperations;
    public H2BookRepository(JdbcOperations jdbcOperations, H2AuthorRepository authorRepository) {
        super(authorRepository);
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public void setupData() {
        Author king = newAuthor("Stephen King");
        Author jp = newAuthor("James Patterson");
        Author dw = newAuthor("Don Winslow");


        authorRepository.saveAll(Arrays.asList(
                king,
                jp,
                dw
        ));

        saveAll(Arrays.asList(
            newBook(king, "The Stand", 1000),
            newBook(king, "Pet Cemetery", 400),
            newBook(jp, "Along Came a Spider", 300),
            newBook(jp, "Double Cross", 300),
            newBook(dw, "The Power of the Dog", 600),
            newBook(dw, "The Border", 700)
        ));
    }

    @Join(value = "author", alias = "auth")
    abstract Book queryByTitle(String title);

    @Transactional(readOnly = true)
    public Author findByName(String name) {
        return jdbcOperations.prepareStatement("SELECT author_.id,author_.name,author_.nick_name,author_books_.id AS _books_id,author_books_.author_id AS _books_author_id,author_books_.title AS _books_title,author_books_.pages AS _books_pages,author_books_.publisher_id AS _books_publisher_id FROM author AS author_ INNER JOIN book author_books_ ON author_.id=author_books_.author_id WHERE (author_.name = ?)", statement -> {
            statement.setString(1, name);
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                Author author = jdbcOperations.readEntity(resultSet, Author.class);
                Set<Book> books = new HashSet<>();
                do {
                    books.add(jdbcOperations.readEntity("_books_", resultSet, Book.class));
                } while (resultSet.next());
                author.setBooks(books);
                return author;
            }
            throw new EmptyResultException();
        });
    }
}
