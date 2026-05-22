from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

engine = create_engine("sqlite:///./inventory.db")
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
